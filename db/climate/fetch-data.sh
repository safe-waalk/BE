#!/usr/bin/env bash
# 기후 경로(폭염/호우) 기능용 원천 데이터 내려받기 → BE/osm-data/ (gitignore 대상)
#   - OSM(도로·건물·가로수·공원): openstreetmap.org map API를 타일로 나눠 요청
#   - 고도: AWS Terrain Tiles (SRTM 1", 공개 버킷)
#   - 무더위쉼터: 서울 열린데이터광장 OA-21065 (CSV)
#   - 침수흔적도: 서울 열린데이터광장 OA-15636 (연도별 SHP zip, 공공누리 1유형)
# 사용: cd BE && ./db/climate/fetch-data.sh
set -euo pipefail

# 한성대입구역~한성대 일대. build_graph.py / load.sh 의 BBOX와 같아야 한다.
S=37.572; W=126.993; N=37.600; E=127.024
STEP=0.008
OUT="osm-data"
mkdir -p "$OUT/tiles" "$OUT/raw/flood"

echo "== OSM 타일"
python3 - "$S" "$W" "$N" "$E" "$STEP" <<'PY' | while read -r name bbox; do
import sys
s, w, n, e, step = map(float, sys.argv[1:])
i = 0
lat = s
while lat < n - 1e-9:
    lon = w
    while lon < e - 1e-9:
        print(f"t{i:02d} {lon:.4f},{lat:.4f},{min(lon+step,e):.4f},{min(lat+step,n):.4f}")
        i += 1
        lon += step
    lat += step
PY
  f="$OUT/tiles/$name.osm"
  if [ -s "$f" ] && grep -q '</osm>' "$f"; then continue; fi
  curl -sS -m 180 -A "osm-loader/1.0" -o "$f" "https://api.openstreetmap.org/api/0.6/map?bbox=$bbox"
  grep -q '</osm>' "$f" || { echo "타일 $name 실패" >&2; exit 1; }
  sleep 1
done
ls "$OUT/tiles" | wc -l

echo "== 고도 타일"
for tile in N37E126 N37E127; do
  [ -s "$OUT/$tile.hgt.gz" ] || curl -sS -m 180 -o "$OUT/$tile.hgt.gz" \
    "https://elevation-tiles-prod.s3.amazonaws.com/skadi/N37/$tile.hgt.gz"
done

echo "== 무더위쉼터 (서울시, CP949 CSV)"
[ -s "$OUT/raw/shelters_seoul.csv" ] || curl -sS -m 120 -A "Mozilla/5.0" -X POST \
  "https://datafile.seoul.go.kr/bigfile/iot/sheet/csv/download.do" \
  -d "srvType=S&infId=OA-21065&serviceKind=1&pageNo=1&gridTotalCnt=10000&ssUserId=SAMPLE_VIEW" \
  -o "$OUT/raw/shelters_seoul.csv"

echo "== 침수흔적도 (서울시, 연도별)"
# 데이터셋 페이지의 파일 seq ↔ 연도 (2015·2021년 파일은 제공되지 않음)
for m in 4:2010 5:2011 6:2012 7:2013 8:2014 9:2016 10:2017 11:2018 12:2019 13:2020 30:2022 31:2023 32:2024 103:2025; do
  seq=${m%%:*}; year=${m##*:}
  [ -s "$OUT/raw/flood/$year.zip" ] && continue
  curl -sS -m 180 -A "Mozilla/5.0" -X POST \
    "https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do?&useCache=false" \
    -d "infId=OA-15636&seqNo=$seq&seq=$seq&infSeq=1" -o "$OUT/raw/flood/$year.zip"
  sleep 1
done
ls -la "$OUT/raw" "$OUT/raw/flood"
