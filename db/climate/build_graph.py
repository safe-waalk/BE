#!/usr/bin/env python3
"""OSM 타일 → 보행 그래프(간선·꼭짓점) + 그늘 공급원 CSV. 표준 라이브러리만 쓴다.

osm2pgrouting은 tunnel·covered 태그를 버리므로 쓰지 않고 직접 파싱한다.

사용:
  python3 db/climate/build_graph.py --osm-data osm-data --out osm-data/work/climate/graph

입력 (--osm-data 아래):
  tiles/t*.osm        OSM API map 응답 타일. 경계가 겹쳐 같은 node/way가 여러 타일에 나온다.
  N37E126.hgt.gz ...  SRTM 1" 고도 타일 (gzip된 big-endian int16, 3601x3601, 좌상단이 북서)

산출 (--out 아래, psql COPY ... (FORMAT csv, HEADER true)로 바로 넣을 수 있다):
  ways.csv           gid,osm_id,source,target,highway,underpass,covered,name,length_m,the_geom
  vertices.csv       id,osm_id,lat,lon,elevation_m,the_geom
  shade_sources.csv  kind,osm_type,osm_id,height_m,buffer_m,geom
  stats.json         빌드 통계 (보고용)
geom 열은 'SRID=4326;...' 형태의 EWKT다.
"""

import argparse
import csv
import glob
import gzip
import json
import math
import os
import re
import sys
import xml.etree.ElementTree as ET
from array import array
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))

# 보행 가능 highway. 차도류도 보도가 따로 그려지지 않은 곳이 많아 포함한다.
WALKABLE_HIGHWAY = {
    "footway", "path", "pedestrian", "steps", "cycleway", "track",
    "residential", "living_street", "service", "unclassified", "road",
    "tertiary", "tertiary_link", "secondary", "secondary_link", "primary", "primary_link",
}
# 지하일 때 PEDESTRIAN(지하보도)으로 보는 highway. 나머지 보행 가능 highway는 ROAD(지하차도).
# cycleway는 국내에서 대부분 보행자 겸용이라 보행 쪽에 넣었다.
PEDESTRIAN_HIGHWAY = {"footway", "path", "pedestrian", "steps", "cycleway"}

FOOT_ALLOWED = {"yes", "designated", "permissive"}
ACCESS_DENIED = {"private", "no"}

DEFAULT_BUILDING_HEIGHT_M = 9.0
LEVEL_HEIGHT_M = 3.2
BUILDING_BUFFER_RATIO = 0.5   # 그림자 폭 = 높이 x 0.5
BUILDING_BUFFER_MAX_M = 15.0  # 고층 건물도 그림자 폭은 15m까지만
TREE_RADIUS_M = 3.5
GREEN_TAGS = (("natural", "wood"), ("landuse", "forest"), ("leisure", "park"))

MIN_EDGE_LENGTH_M = 0.01      # 길이 0인 간선(같은 좌표의 서로 다른 node) 방지
EARTH_RADIUS_M = 6371008.8
SRTM_SIZE = 3601
SRTM_VOID = -32768


def read_bbox(path):
    values = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = float(value.strip())
    return values["BBOX_S"], values["BBOX_W"], values["BBOX_N"], values["BBOX_E"]


# ── OSM 파싱 ────────────────────────────────────────────────

def parse_tiles(paths):
    """타일 전체를 읽어 id로 중복을 제거한다. 같은 id가 여러 번이면 version이 큰 것을 남긴다."""
    nodes = {}       # id -> (lat, lon, version)
    trees = {}       # natural=tree node id -> version
    ways = {}        # id -> (version, [node ids], tags)
    relations = {}   # id -> (version, [(type, ref, role)], tags)
    raw = Counter()

    for path in paths:
        for _, el in ET.iterparse(path, events=("end",)):
            tag = el.tag
            if tag == "node":
                raw["node"] += 1
                nid = int(el.get("id"))
                ver = int(el.get("version", 0))
                old = nodes.get(nid)
                if old is None or ver > old[2]:
                    nodes[nid] = (float(el.get("lat")), float(el.get("lon")), ver)
                    if any(t.get("k") == "natural" and t.get("v") == "tree" for t in el.iter("tag")):
                        trees[nid] = ver
                    else:
                        trees.pop(nid, None)
                el.clear()
            elif tag == "way":
                raw["way"] += 1
                wid = int(el.get("id"))
                ver = int(el.get("version", 0))
                old = ways.get(wid)
                if old is None or ver > old[0]:
                    refs = [int(nd.get("ref")) for nd in el.iter("nd")]
                    tags = {t.get("k"): t.get("v") for t in el.iter("tag")}
                    ways[wid] = (ver, refs, tags)
                el.clear()
            elif tag == "relation":
                raw["relation"] += 1
                rid = int(el.get("id"))
                ver = int(el.get("version", 0))
                old = relations.get(rid)
                if old is None or ver > old[0]:
                    members = [(m.get("type"), int(m.get("ref")), m.get("role") or "")
                               for m in el.iter("member")]
                    tags = {t.get("k"): t.get("v") for t in el.iter("tag")}
                    relations[rid] = (ver, members, tags)
                el.clear()
    return nodes, set(trees), ways, relations, raw


def is_walkable(tags):
    if tags.get("highway") not in WALKABLE_HIGHWAY:
        return False
    foot = tags.get("foot")
    if foot == "no":
        return False
    if foot in FOOT_ALLOWED:
        return True
    return tags.get("access") not in ACCESS_DENIED


def parse_layer(tags):
    m = re.match(r"\s*(-?\d+)", tags.get("layer", ""))
    return int(m.group(1)) if m else 0


def classify_underpass(tags):
    # building_passage는 건물 1층을 관통하는 지상 통로라 지하로 보지 않는다(covered로만 처리).
    tunnel = tags.get("tunnel")
    underground = (tunnel not in (None, "no", "building_passage")) or parse_layer(tags) < 0
    if not underground:
        return "NONE"
    return "PEDESTRIAN" if tags.get("highway") in PEDESTRIAN_HIGHWAY else "ROAD"


def is_covered(tags):
    covered = tags.get("covered")
    return (covered is not None and covered != "no") or tags.get("tunnel") == "building_passage"


# ── 고도 ────────────────────────────────────────────────────

class Srtm:
    """SRTM 1" hgt 타일 쌍선형 보간. void(-32768)는 빼고 남은 가중치로 다시 정규화한다."""

    def __init__(self, osm_data):
        self.tiles = {}
        for path in sorted(glob.glob(os.path.join(osm_data, "*.hgt.gz"))):
            m = re.match(r"([NS])(\d+)([EW])(\d+)\.hgt\.gz$", os.path.basename(path))
            if not m:
                continue
            lat0 = int(m.group(2)) * (1 if m.group(1) == "N" else -1)
            lon0 = int(m.group(4)) * (1 if m.group(3) == "E" else -1)
            data = array("h")
            with gzip.open(path, "rb") as f:
                data.frombytes(f.read())
            if len(data) != SRTM_SIZE * SRTM_SIZE:
                raise SystemExit(f"{path}: 크기가 {SRTM_SIZE}x{SRTM_SIZE}가 아니다 ({len(data)})")
            if sys.byteorder == "little":
                data.byteswap()
            self.tiles[(lat0, lon0)] = data
        self.void_cells = 0

    def elevation(self, lat, lon):
        lat0, lon0 = math.floor(lat), math.floor(lon)
        data = self.tiles.get((lat0, lon0))
        if data is None:
            return None
        n = SRTM_SIZE - 1
        y = (lat0 + 1 - lat) * n   # 0 = 북쪽 끝 행
        x = (lon - lon0) * n       # 0 = 서쪽 끝 열
        r, c = min(int(y), n - 1), min(int(x), n - 1)
        fy, fx = y - r, x - c
        samples = (
            (data[r * SRTM_SIZE + c], (1 - fy) * (1 - fx)),
            (data[r * SRTM_SIZE + c + 1], (1 - fy) * fx),
            (data[(r + 1) * SRTM_SIZE + c], fy * (1 - fx)),
            (data[(r + 1) * SRTM_SIZE + c + 1], fy * fx),
        )
        valid = [(v, w) for v, w in samples if v != SRTM_VOID]
        if len(valid) < 4:
            self.void_cells += 1
        if not valid:
            return None
        total = sum(w for _, w in valid)
        if total < 1e-12:
            return sum(v for v, _ in valid) / len(valid)
        return sum(v * w for v, w in valid) / total


# ── 기하 ────────────────────────────────────────────────────

def haversine_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))


def coords_text(points):
    return ", ".join(f"{lon:.7f} {lat:.7f}" for lat, lon in points)


def linestring_ewkt(points):
    return f"SRID=4326;LINESTRING({coords_text(points)})"


# ── 그래프 ──────────────────────────────────────────────────

def build_graph(nodes, ways, bbox, srtm, stats):
    s, w, n, e = bbox

    def in_bbox(nid):
        lat, lon, _ = nodes[nid]
        return s <= lat <= n and w <= lon <= e

    walk_ways = []
    for wid in sorted(ways):
        _, refs, tags = ways[wid]
        if not is_walkable(tags):
            continue
        present = [r for r in refs if r in nodes]
        stats["missing_node_refs"] += len(refs) - len(present)
        seq = [r for i, r in enumerate(present) if i == 0 or r != present[i - 1]]
        if len(seq) >= 2:
            walk_ways.append((wid, seq, tags))
    stats["walkable_ways"] = len(walk_ways)

    # 서로 다른 보행 way 몇 개에 등장하는지
    way_count = Counter()
    for _, seq, _ in walk_ways:
        way_count.update(set(seq))

    vertex_id = {}    # osm node id -> 새 꼭짓점 id (1부터)
    vertex_rows = []
    edge_rows = []

    def vid(nid):
        if nid not in vertex_id:
            vertex_id[nid] = len(vertex_id) + 1
            lat, lon, _ = nodes[nid]
            elev = srtm.elevation(lat, lon)
            vertex_rows.append([
                vertex_id[nid], nid, f"{lat:.7f}", f"{lon:.7f}",
                None if elev is None else f"{elev:.2f}",
                f"SRID=4326;POINT({lon:.7f} {lat:.7f})",
            ])
        return vertex_id[nid]

    def emit(wid, tags, part):
        if not any(in_bbox(r) for r in part):
            stats["edges_outside_bbox"] += 1
            return
        points = [(nodes[r][0], nodes[r][1]) for r in part]
        length = sum(haversine_m(*points[i], *points[i + 1]) for i in range(len(points) - 1))
        if length < MIN_EDGE_LENGTH_M:
            stats["zero_length_edges"] += 1
            length = MIN_EDGE_LENGTH_M
        edge_rows.append([
            len(edge_rows) + 1, wid, vid(part[0]), vid(part[-1]),
            tags.get("highway"), classify_underpass(tags), "true" if is_covered(tags) else "false",
            tags.get("name"), f"{length:.3f}", linestring_ewkt(points),
        ])

    for wid, seq, tags in walk_ways:
        last = len(seq) - 1
        seen = Counter(seq)
        cuts = [i for i, r in enumerate(seq)
                if i == 0 or i == last or way_count[r] >= 2 or seen[r] >= 2]
        for a, b in zip(cuts, cuts[1:]):
            part = seq[a:b + 1]
            if part[0] == part[-1]:
                # 고리는 가운데 node에서 한 번 더 잘라 source == target 간선을 만들지 않는다
                mid = len(part) // 2
                emit(wid, tags, part[:mid + 1])
                emit(wid, tags, part[mid:])
                stats["loops_split"] += 1
            else:
                emit(wid, tags, part)

    stats["edges"] = len(edge_rows)
    stats["vertices"] = len(vertex_rows)
    stats["underpass"] = dict(Counter(row[5] for row in edge_rows))
    stats["covered_edges"] = sum(1 for row in edge_rows if row[6] == "true")
    stats["vertices_without_elevation"] = sum(1 for row in vertex_rows if row[4] is None)
    return edge_rows, vertex_rows


# ── 그늘 공급원 ─────────────────────────────────────────────

def parse_height(tags):
    m = re.match(r"\s*(\d+(?:\.\d+)?)\s*(m|ft|')?", tags.get("height", ""))
    if m:
        h = float(m.group(1))
        if m.group(2) in ("ft", "'"):
            h *= 0.3048
        if h > 0:
            return h, "height"
    m = re.match(r"\s*(\d+(?:\.\d+)?)", tags.get("building:levels", ""))
    if m and float(m.group(1)) > 0:
        return float(m.group(1)) * LEVEL_HEIGHT_M, "levels"
    return DEFAULT_BUILDING_HEIGHT_M, "default"


def is_building(tags):
    b = tags.get("building")
    if b is None or b == "no":
        return False
    # 지하 건물은 지상에 그림자를 만들지 않는다
    return tags.get("location") != "underground" and parse_layer(tags) >= 0


def is_green(tags):
    return any(tags.get(k) == v for k, v in GREEN_TAGS)


def building_row(osm_type, osm_id, tags, geom, stats):
    height, origin = parse_height(tags)
    stats["building_height_" + origin] += 1
    buffer_m = min(height * BUILDING_BUFFER_RATIO, BUILDING_BUFFER_MAX_M)
    return ["building", osm_type, osm_id, f"{height:.2f}", f"{buffer_m:.2f}", geom]


def build_shade_sources(nodes, trees, ways, relations, stats):
    rows = []

    for nid in sorted(trees):
        if nid in nodes:
            lat, lon, _ = nodes[nid]
            rows.append(["tree", "node", nid, None, f"{TREE_RADIUS_M:.2f}",
                         f"SRID=4326;POINT({lon:.7f} {lat:.7f})"])

    for wid in sorted(ways):
        _, refs, tags = ways[wid]
        points = [(nodes[r][0], nodes[r][1]) for r in refs if r in nodes]
        if len(points) < 2:
            continue
        if tags.get("natural") == "tree_row":
            rows.append(["tree_row", "way", wid, None, f"{TREE_RADIUS_M:.2f}", linestring_ewkt(points)])
            continue
        closed = len(refs) >= 4 and refs[0] == refs[-1] and len(points) == len(refs)
        if not closed:
            continue
        geom = linestring_ewkt(points)   # 닫힌 선. SQL에서 ST_BuildArea로 면을 만든다.
        if is_building(tags):
            rows.append(building_row("way", wid, tags, geom, stats))
        if is_green(tags):
            rows.append(["green", "way", wid, None, "0", geom])

    # multipolygon 관계: 구성 way의 선을 모아 내보내면 ST_BuildArea가 바깥/안쪽 고리를 알아서 맞춘다.
    # 타일 밖 구성원이 빠져 고리가 닫히지 않으면 면이 안 만들어지고 그 관계는 버려진다.
    for rid in sorted(relations):
        _, members, tags = relations[rid]
        if tags.get("type") != "multipolygon":
            continue
        building, green = is_building(tags), is_green(tags)
        if not (building or green):
            continue
        lines = []
        for mtype, ref, _ in members:
            if mtype != "way" or ref not in ways:
                continue
            pts = [(nodes[r][0], nodes[r][1]) for r in ways[ref][1] if r in nodes]
            if len(pts) >= 2:
                lines.append(f"({coords_text(pts)})")
        if not lines:
            continue
        geom = f"SRID=4326;MULTILINESTRING({', '.join(lines)})"
        if building:
            rows.append(building_row("relation", rid, tags, geom, stats))
        if green:
            rows.append(["green", "relation", rid, None, "0", geom])

    stats["shade_sources"] = dict(Counter(row[0] for row in rows))
    return rows


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(header)
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--osm-data", required=True, help="tiles/ 와 *.hgt.gz 가 있는 디렉터리")
    parser.add_argument("--out", required=True, help="CSV를 쓸 디렉터리")
    parser.add_argument("--bbox-file", default=os.path.join(HERE, "bbox.env"))
    args = parser.parse_args()

    bbox = read_bbox(args.bbox_file)
    tiles = sorted(glob.glob(os.path.join(args.osm_data, "tiles", "t*.osm")))
    if not tiles:
        raise SystemExit(f"{args.osm_data}/tiles/t*.osm 이 없다")
    srtm = Srtm(args.osm_data)
    if not srtm.tiles:
        raise SystemExit(f"{args.osm_data}/*.hgt.gz 가 없다")
    os.makedirs(args.out, exist_ok=True)

    stats = Counter()
    nodes, trees, ways, relations, raw = parse_tiles(tiles)
    edge_rows, vertex_rows = build_graph(nodes, ways, bbox, srtm, stats)
    shade_rows = build_shade_sources(nodes, trees, ways, relations, stats)

    write_csv(os.path.join(args.out, "ways.csv"),
              ["gid", "osm_id", "source", "target", "highway", "underpass", "covered", "name",
               "length_m", "the_geom"], edge_rows)
    write_csv(os.path.join(args.out, "vertices.csv"),
              ["id", "osm_id", "lat", "lon", "elevation_m", "the_geom"], vertex_rows)
    write_csv(os.path.join(args.out, "shade_sources.csv"),
              ["kind", "osm_type", "osm_id", "height_m", "buffer_m", "geom"], shade_rows)

    report = {
        "tiles": len(tiles),
        "raw_elements": dict(raw),
        "unique_nodes": len(nodes),
        "unique_ways": len(ways),
        "unique_relations": len(relations),
        "srtm_tiles": sorted(f"{lat}/{lon}" for lat, lon in srtm.tiles),
        "srtm_void_touching_vertices": srtm.void_cells,
        **stats,
    }
    with open(os.path.join(args.out, "stats.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2, sort_keys=True)
    json.dump(report, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
    print()


if __name__ == "__main__":
    main()
