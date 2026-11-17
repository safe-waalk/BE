#!/usr/bin/env python3
"""서울시 무더위쉼터 CSV(CP949) → 적재용 UTF-8 CSV. 표준 라이브러리만 쓴다.

사용:
  python3 db/climate/prepare_shelters.py osm-data/raw/shelters_seoul.csv out.csv

bbox 안의 쉼터만 남긴다. 위경도가 비었거나 뒤바뀐 행은 EPSG:5186 X/Y로 보완하도록
x5186/y5186도 함께 내보내고, 그런 행은 bbox 판정을 SQL(load.sh)에 맡긴다.
산출 열: name,facility_type,address,open_time,close_time,lon,lat,x5186,y5186
"""

import csv
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.dont_write_bytecode = True  # db/climate에 __pycache__를 남기지 않는다
sys.path.insert(0, HERE)
from build_graph import read_bbox  # noqa: E402

COLUMNS = {
    "name": "쉼터명칭",
    "facility_type": "시설구분2",
    "address": "도로명주소",
    "open_time": "기본운영_시작시간",
    "close_time": "기본운영_종료시간",
    "lon": "경도",
    "lat": "위도",
    "x5186": "X좌표(EPSG:5186)",
    "y5186": "Y좌표(EPSG:5186)",
}


def to_float(text):
    try:
        value = float((text or "").strip())
    except ValueError:
        return None
    return value if value != 0 else None


def clean(text):
    text = (text or "").strip()
    return text or None


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src, dst = sys.argv[1], sys.argv[2]
    s, w, n, e = read_bbox(os.path.join(HERE, "bbox.env"))

    with open(src, encoding="cp949", newline="") as f:
        reader = csv.DictReader(f)
        missing = [col for col in COLUMNS.values() if col not in (reader.fieldnames or [])]
        if missing:
            raise SystemExit(f"헤더에 없는 열: {missing}\n실제 헤더: {reader.fieldnames}")
        rows = list(reader)

    stats = {"total": len(rows), "swapped_lonlat": 0, "xy_only": 0, "no_coords": 0,
             "duplicates": 0, "in_bbox": 0, "xy_candidates": 0}
    seen = set()
    out = []
    for row in rows:
        rec = {key: clean(row[col]) for key, col in COLUMNS.items()}
        # 원본에서 가운뎃점(·)이 '?'로 깨져 있다(예: 복지?문화?체육시설). 분류명에는 '?'가 올 일이 없어 되돌린다.
        if rec["facility_type"]:
            rec["facility_type"] = rec["facility_type"].replace("?", "·")
        lon, lat = to_float(rec["lon"]), to_float(rec["lat"])
        x, y = to_float(rec["x5186"]), to_float(rec["y5186"])
        if lon is not None and lat is not None and lat > 90 and lon < 90:
            lon, lat = lat, lon
            stats["swapped_lonlat"] += 1
        lonlat_ok = lon is not None and lat is not None and -180 <= lon <= 180 and -90 <= lat <= 90
        if not lonlat_ok:
            lon = lat = None
            if x is None or y is None:
                stats["no_coords"] += 1
                continue
            stats["xy_only"] += 1
        elif not (s <= lat <= n and w <= lon <= e):
            continue

        key = (rec["name"], rec["address"], lon, lat, x, y)
        if key in seen:
            stats["duplicates"] += 1
            continue
        seen.add(key)
        stats["in_bbox" if lonlat_ok else "xy_candidates"] += 1
        out.append([rec["name"], rec["facility_type"], rec["address"], rec["open_time"], rec["close_time"],
                    None if lon is None else f"{lon:.7f}", None if lat is None else f"{lat:.7f}",
                    None if x is None else f"{x:.3f}", None if y is None else f"{y:.3f}"])

    with open(dst, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["name", "facility_type", "address", "open_time", "close_time",
                         "lon", "lat", "x5186", "y5186"])
        writer.writerows(out)
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
