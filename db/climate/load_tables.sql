-- 스테이징(climate_work.*) → 계약 테이블. load.sh가 --single-transaction으로 실행한다.
-- 필요한 psql 변수: bbox_s, bbox_w, bbox_n, bbox_e
-- 매번 비우고 다시 넣으며 id는 입력 순서대로 매겨지므로 몇 번 돌려도 같은 결과가 된다.

-- ── 보행 그래프 ─────────────────────────────────────────────
TRUNCATE ways, ways_vertices_pgr;

INSERT INTO ways_vertices_pgr (id, osm_id, lat, lon, elevation_m, the_geom)
SELECT id, osm_id, lat, lon, elevation_m, the_geom
FROM climate_work.vertices
ORDER BY id;

-- shade_ratio·in_flood_zone·near_shelter·heat_cost·rain_cost는 compute_costs.sql이 채운다.
INSERT INTO ways (gid, osm_id, source, target, highway, underpass, covered, name,
                  length_m, cost, reverse_cost, safety_score, safety_cost, the_geom)
SELECT gid, osm_id, source, target, highway, underpass, covered, name,
       length_m, length_m, length_m, NULL, NULL, the_geom
FROM climate_work.ways
ORDER BY gid;

-- ── 침수흔적도 ───────────────────────────────────────────────
-- ogr2ogr가 bbox로 잘라 4326으로 바꿔 둔 것. 자르면서 생긴 선·점 조각은 버리고 면만 남긴다.
-- year는 피처의 F_YR(침수 연도)을 쓰고, 없거나 연도 꼴이 아니면 파일 연도를 쓴다.
-- 같은 연도에 모양까지 똑같은 폴리곤이 여러 파일에 있으면 하나만 남긴다.
TRUNCATE flood_trace RESTART IDENTITY;

INSERT INTO flood_trace (year, source, geom)
SELECT year, '서울 열린데이터광장 OA-15636 서울시 침수흔적도 (' || file_year || '년 파일)', geom
FROM (
    SELECT DISTINCT ON (year, ST_AsBinary(geom)) row_no, year, file_year, geom
    FROM (
        SELECT row_no, file_year,
               CASE WHEN f_yr ~ '^\s*(19|20)\d\d\s*$' THEN trim(f_yr)::INT ELSE file_year END AS year,
               ST_Multi(ST_CollectionExtract(ST_MakeValid(ST_SetSRID(geom, 4326)), 3)) AS geom
        FROM climate_work.flood_raw
    ) f
    WHERE NOT ST_IsEmpty(geom)
      AND ST_Area(geom::geography) > 0
    ORDER BY year, ST_AsBinary(geom), row_no
) d
ORDER BY year, row_no;

-- ── 무더위쉼터 ───────────────────────────────────────────────
-- 위경도가 없거나 이상한 행은 EPSG:5186 X/Y로 보완한 뒤 bbox로 거른다.
TRUNCATE cooling_shelter RESTART IDENTITY;

INSERT INTO cooling_shelter (name, facility_type, address, open_time, close_time, source, geom)
SELECT name, facility_type, address, open_time, close_time, '서울 열린데이터광장 OA-21065', geom
FROM (
    SELECT row_no, name, facility_type, address, open_time, close_time,
           COALESCE(ST_SetSRID(ST_MakePoint(lon, lat), 4326),
                    ST_Transform(ST_SetSRID(ST_MakePoint(x5186, y5186), 5186), 4326)) AS geom
    FROM climate_work.shelter_raw
) s
WHERE ST_Intersects(geom, ST_MakeEnvelope(:bbox_w, :bbox_s, :bbox_e, :bbox_n, 4326))
ORDER BY row_no;
