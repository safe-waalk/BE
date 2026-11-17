#!/usr/bin/env bash
# 기후 경로(폭염/호우) 데이터를 로컬 PostGIS+pgRouting DB에 처음부터 끝까지 적재한다.
#   확장/스키마 → 보행 그래프 빌드·적재 → 침수흔적도 → 무더위쉼터 → 비용 컬럼 계산 → 검증 쿼리
# 몇 번을 돌려도 같은 결과가 된다(계약 테이블은 비우고 다시 채우고, id는 입력 순서로 매긴다).
#
# 사용: cd BE && ./db/climate/load.sh
# 환경변수(선택):
#   OSM_DATA  입력 디렉터리 (기본 osm-data, 읽을 수 없으면 osm-data/work/input)
#             tiles/t*.osm, N37E126.hgt.gz, N37E127.hgt.gz, raw/shelters_seoul.csv, raw/flood/<연도>.zip
#   WORK      중간 산출물 디렉터리 (기본 osm-data/work/climate)
#   PGHOST/PGPORT/PGUSER/PGDATABASE/PGPASSWORD  기본은 docker-compose.local.yml의 로컬 DB
#   FLOOD_S_SRS_<연도>  그 연도 SHP에 .prj가 없을 때만 원본 좌표계를 지정 (예: FLOOD_S_SRS_2012=EPSG:5174)
#   FLOOD_NEAR_M        침수흔적에서 몇 m 이내 간선을 in_flood_zone으로 볼지 (기본 10, 0이면 교차 기준)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
HERE=db/climate
# shellcheck source=bbox.env
source "$HERE/bbox.env"

export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5433}" PGUSER="${PGUSER:-safewalk}"
export PGDATABASE="${PGDATABASE:-safewalk}" PGPASSWORD="${PGPASSWORD:-safewalk}"
PSQL=(psql -X -q -v ON_ERROR_STOP=1)
BBOX_VARS=(-v "bbox_s=$BBOX_S" -v "bbox_w=$BBOX_W" -v "bbox_n=$BBOX_N" -v "bbox_e=$BBOX_E")

readable() { head -c 1 "$1" > /dev/null 2>&1; }
step() { printf '\n==== [%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# ── 0. 입력 확인 ─────────────────────────────────────────────
if [ -z "${OSM_DATA:-}" ]; then
  OSM_DATA=osm-data
  if ! readable "$OSM_DATA/tiles/t00.osm" && readable osm-data/work/input/tiles/t00.osm; then
    OSM_DATA=osm-data/work/input
  fi
fi
WORK="${WORK:-osm-data/work/climate}"

step "입력 확인 (OSM_DATA=$OSM_DATA, WORK=$WORK)"
missing=0
inputs=("$OSM_DATA/N37E126.hgt.gz" "$OSM_DATA/N37E127.hgt.gz" "$OSM_DATA/raw/shelters_seoul.csv")
for i in $(seq -w 0 15); do inputs+=("$OSM_DATA/tiles/t$i.osm"); done
for f in "${inputs[@]}"; do
  if ! readable "$f"; then echo "읽을 수 없음: $f" >&2; missing=1; fi
done
flood_zips=()
for z in "$OSM_DATA"/raw/flood/*.zip; do
  if readable "$z"; then flood_zips+=("$z"); else echo "읽을 수 없음: $z" >&2; missing=1; fi
done
[ "${#flood_zips[@]}" -gt 0 ] || { echo "침수흔적도 zip이 없다: $OSM_DATA/raw/flood/*.zip" >&2; missing=1; }
[ "$missing" -eq 0 ] || { echo "입력 파일을 읽을 수 없어 중단한다." >&2; exit 1; }
for t in "$OSM_DATA"/tiles/t*.osm; do
  tail -c 64 "$t" | grep -q '</osm>' || { echo "잘린 OSM 타일: $t" >&2; exit 1; }
done
mkdir -p "$WORK/graph" "$WORK/flood"
echo "OSM 타일 16개, 고도 타일 2개, 무더위쉼터 CSV 1개, 침수흔적도 zip ${#flood_zips[@]}개 확인"

# ── 1. 확장/스키마 ───────────────────────────────────────────
step "확장/스키마 (db/climate/schema.sql)"
"${PSQL[@]}" -c 'SET client_min_messages = warning' -f "$HERE/schema.sql"
# 이미 다른 모양의 ways(예: osm2pgrouting 산출물)가 있으면 IF NOT EXISTS가 그대로 두므로 여기서 막는다.
"${PSQL[@]}" <<'SQL'
DO $$
DECLARE
    expected TEXT := 'gid:bigint,osm_id:bigint,source:bigint,target:bigint,highway:text,underpass:text,'
                  || 'covered:boolean,name:text,length_m:double precision,cost:double precision,'
                  || 'reverse_cost:double precision,shade_ratio:double precision,in_flood_zone:boolean,'
                  || 'near_shelter:boolean,heat_cost:double precision,rain_cost:double precision,'
                  || 'safety_score:double precision,safety_cost:double precision,the_geom:USER-DEFINED';
    actual TEXT;
BEGIN
    SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY ordinal_position) INTO actual
    FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'ways';
    IF actual IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'ways 테이블이 DB 계약과 다르다: %', actual;
    END IF;
END $$;
SQL

"${PSQL[@]}" <<'SQL'
SET client_min_messages = warning;
DROP SCHEMA IF EXISTS climate_work CASCADE;
CREATE SCHEMA climate_work;
CREATE TABLE climate_work.ways (
    gid BIGINT, osm_id BIGINT, source BIGINT, target BIGINT, highway TEXT, underpass TEXT,
    covered BOOLEAN, name TEXT, length_m DOUBLE PRECISION, the_geom GEOMETRY(LineString, 4326));
CREATE TABLE climate_work.vertices (
    id BIGINT, osm_id BIGINT, lat DOUBLE PRECISION, lon DOUBLE PRECISION,
    elevation_m DOUBLE PRECISION, the_geom GEOMETRY(Point, 4326));
CREATE TABLE climate_work.shade_src (
    kind TEXT, osm_type TEXT, osm_id BIGINT, height_m DOUBLE PRECISION, buffer_m DOUBLE PRECISION,
    geom GEOMETRY(Geometry, 4326));
CREATE TABLE climate_work.flood_raw (row_no BIGSERIAL, file_year INT, f_yr TEXT, geom GEOMETRY);
CREATE TABLE climate_work.shelter_raw (
    row_no BIGSERIAL, name TEXT, facility_type TEXT, address TEXT, open_time TEXT, close_time TEXT,
    lon DOUBLE PRECISION, lat DOUBLE PRECISION, x5186 DOUBLE PRECISION, y5186 DOUBLE PRECISION);
SQL

copy_csv() {  # copy_csv <테이블(열 목록)> <csv 파일>
  "${PSQL[@]}" -c "\\copy $1 FROM pstdin WITH (FORMAT csv, HEADER true)" < "$2"
}

# ── 2. 보행 그래프 빌드·적재 ─────────────────────────────────
step "보행 그래프 빌드 (build_graph.py)"
python3 "$HERE/build_graph.py" --osm-data "$OSM_DATA" --out "$WORK/graph" > "$WORK/graph/build.log"
cat "$WORK/graph/build.log"
copy_csv climate_work.ways "$WORK/graph/ways.csv"
copy_csv climate_work.vertices "$WORK/graph/vertices.csv"
copy_csv climate_work.shade_src "$WORK/graph/shade_sources.csv"

# ── 3. 침수흔적도 ───────────────────────────────────────────
# zip 안 파일명이 CP949 한글이라 그대로 풀면 깨진다. zipfile로 열어 확장자별로
# flood_<연도>_<n>.<ext> 라는 ASCII 이름으로 풀고, ogr2ogr로 bbox만 잘라 4326 WKT CSV로 만든다.
# 파일 연도와 피처의 F_YR(침수 연도)이 다를 수 있어(2022 파일에 F_YR=2023 행이 섞여 있다) 둘 다 남긴다.
step "침수흔적도 (${#flood_zips[@]}개 연도)"
for zip in "${flood_zips[@]}"; do
  year="$(basename "$zip" .zip)"
  shps="$(python3 -I "$HERE/unzip_flood.py" "$zip" "$WORK/flood/$year" "$year")"
  [ -n "$shps" ] || { echo "$zip 안에 .shp가 없다" >&2; exit 1; }
  while IFS= read -r shp; do
    # 원본 좌표계는 .prj로 정한다. .prj가 없으면 FLOOD_S_SRS_<연도>로 지정해야만 진행한다.
    srs_var="FLOOD_S_SRS_$year"
    s_srs_opt="" s_srs_val=""
    if [ ! -s "${shp%.shp}.prj" ]; then
      [ -n "${!srs_var:-}" ] || { echo "$shp 에 .prj가 없다. $srs_var=EPSG:xxxx 로 원본 좌표계를 지정하라" >&2; exit 1; }
      s_srs_opt=-s_srs s_srs_val="${!srs_var}"
    fi
    info="$(ogrinfo -so -al "$shp" 2>/dev/null)"
    srs_name="$(printf '%s\n' "$info" | awk '/^Layer SRS WKT:/{getline; print; exit}')"
    features="$(printf '%s\n' "$info" | awk -F': ' '/^Feature Count:/{print $2; exit}')"
    if printf '%s\n' "$info" | grep -q '^F_YR: '; then
      fields=F_YR columns="geom, f_yr"
    else
      fields="" columns="geom"
    fi
    out="${shp%.shp}.csv"
    rm -f "$out"   # CSV 드라이버는 -overwrite로 기존 파일을 덮지 못한다(이전 실행이 만든 중간 산출물)
    # bash 3.2(macOS 기본)는 빈 배열을 set -u에서 못 펼치므로 -s_srs는 있을 때만 붙인다
    ogr2ogr -f CSV "$out" "$shp" -overwrite ${s_srs_opt:+"$s_srs_opt" "$s_srs_val"} -t_srs EPSG:4326 \
      -spat "$BBOX_W" "$BBOX_S" "$BBOX_E" "$BBOX_N" -spat_srs EPSG:4326 \
      -clipdst "$BBOX_W" "$BBOX_S" "$BBOX_E" "$BBOX_N" \
      -makevalid -nlt PROMOTE_TO_MULTI -select "$fields" -lco GEOMETRY=AS_WKT
    kept=$(( $(wc -l < "$out") - 1 ))
    printf '  %s  %-20s 원본 %6s개 → bbox %4s개  %s\n' \
      "$year" "$(basename "$shp")" "$features" "$kept" "${s_srs_val:-$srs_name}"
    copy_csv "climate_work.flood_raw($columns)" "$out"
    "${PSQL[@]}" -c "UPDATE climate_work.flood_raw SET file_year = $year WHERE file_year IS NULL"
  done <<< "$shps"
done

# ── 4. 무더위쉼터 ───────────────────────────────────────────
step "무더위쉼터 (CP949 CSV → bbox)"
python3 "$HERE/prepare_shelters.py" "$OSM_DATA/raw/shelters_seoul.csv" "$WORK/shelters.csv"
copy_csv "climate_work.shelter_raw(name, facility_type, address, open_time, close_time, lon, lat, x5186, y5186)" \
  "$WORK/shelters.csv"

step "계약 테이블로 옮기기 (load_tables.sql)"
"${PSQL[@]}" --single-transaction "${BBOX_VARS[@]}" -f "$HERE/load_tables.sql"

# ── 5. 비용 컬럼 계산 ───────────────────────────────────────
step "그늘 비율·플래그·비용 계산 (compute_costs.sql)"
"${PSQL[@]}" --single-transaction ${FLOOD_NEAR_M:+-v "flood_near_m=$FLOOD_NEAR_M"} \
  -c 'SET client_min_messages = warning' -f "$HERE/compute_costs.sql"

# ── 6. 검증 ─────────────────────────────────────────────────
step "검증 (verify.sql)"
psql -X -v ON_ERROR_STOP=1 -f "$HERE/verify.sql" | tee "$WORK/verify.txt"
step "완료 (${SECONDS}s). 검증 결과: $WORK/verify.txt"
