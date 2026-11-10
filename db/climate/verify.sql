-- 적재 결과 검증. load.sh가 마지막에 실행하며 따로 돌려도 된다(읽기 전용).
--   psql -h localhost -p 5433 -U safewalk -d safewalk -f db/climate/verify.sql
-- 시연 구간: 한성대입구역(37.5884, 127.0062) → 한성대학교(37.5826, 127.0103)
\pset footer off
\set QUIET on
SET client_min_messages = warning;
\set slat 37.5884
\set slng 127.0062
\set elat 37.5826
\set elng 127.0103

\echo '== 테이블별 행 수'
SELECT 'ways' AS tbl, count(*) FROM ways
UNION ALL SELECT 'ways_vertices_pgr', count(*) FROM ways_vertices_pgr
UNION ALL SELECT 'cooling_shelter', count(*) FROM cooling_shelter
UNION ALL SELECT 'flood_trace', count(*) FROM flood_trace
UNION ALL SELECT 'crime_zone', count(*) FROM crime_zone
UNION ALL SELECT 'cctv', count(*) FROM cctv
UNION ALL SELECT 'security_light', count(*) FROM security_light
UNION ALL SELECT 'safety_bell', count(*) FROM safety_bell
UNION ALL SELECT 'report', count(*) FROM report;

\echo '== length_m vs ST_Length(the_geom::geography)'
SELECT round(sum(length_m)::numeric, 1)                                   AS sum_length_m,
       round(sum(ST_Length(the_geom::geography))::numeric, 1)            AS sum_geography_m,
       round(max(abs(length_m - ST_Length(the_geom::geography)))::numeric, 3) AS max_abs_diff_m,
       round((100 * max(abs(length_m - ST_Length(the_geom::geography))
                        / ST_Length(the_geom::geography))
                    FILTER (WHERE ST_Length(the_geom::geography) > 1))::numeric, 3) AS max_rel_diff_pct_over_1m,
       min(ST_NPoints(the_geom))                                          AS min_points,
       round(avg(ST_NPoints(the_geom))::numeric, 2)                       AS avg_points,
       max(ST_NPoints(the_geom))                                          AS max_points,
       count(*) FILTER (WHERE ST_NPoints(the_geom) > 2)                   AS edges_with_shape_points
FROM ways;

\echo '== shade_ratio 분포'
SELECT round(min(shade_ratio)::numeric, 3) AS min,
       round(avg(shade_ratio)::numeric, 3) AS avg,
       round(max(shade_ratio)::numeric, 3) AS max,
       round((sum(length_m * shade_ratio) / sum(length_m))::numeric, 3) AS length_weighted_avg,
       round(100.0 * count(*) FILTER (WHERE shade_ratio = 0) / count(*), 1) AS pct_zero,
       round(100.0 * count(*) FILTER (WHERE shade_ratio = 1) / count(*), 1) AS pct_one
FROM ways;

\echo '== underpass 값별 간선 수 / covered'
SELECT underpass, count(*) AS edges, count(*) FILTER (WHERE covered) AS covered_edges,
       round(sum(length_m)::numeric, 1) AS meters
FROM ways GROUP BY underpass ORDER BY underpass;

\echo '== in_flood_zone / near_shelter true 간선 수'
SELECT count(*) FILTER (WHERE in_flood_zone) AS in_flood_zone_edges,
       round(sum(length_m) FILTER (WHERE in_flood_zone)::numeric, 1) AS in_flood_zone_m,
       count(*) FILTER (WHERE near_shelter) AS near_shelter_edges,
       round(sum(length_m) FILTER (WHERE near_shelter)::numeric, 1) AS near_shelter_m
FROM ways;

\echo '== bbox 안 침수흔적 폴리곤 수 (연도별)'
SELECT year, count(*) AS polygons, round(sum(ST_Area(geom::geography))::numeric, 0) AS area_m2
FROM flood_trace GROUP BY ROLLUP (year) ORDER BY year NULLS LAST;

\echo '== 무더위쉼터 시설 유형별'
SELECT facility_type, count(*) FROM cooling_shelter GROUP BY facility_type ORDER BY count(*) DESC;

\echo '== elevation_m (꼭짓점)'
SELECT round(min(elevation_m)::numeric, 1) AS min, round(avg(elevation_m)::numeric, 1) AS avg,
       round(max(elevation_m)::numeric, 1) AS max, count(*) FILTER (WHERE elevation_m IS NULL) AS nulls
FROM ways_vertices_pgr;

\echo '== 비용 컬럼 이상값 (전부 0이어야 한다)'
SELECT count(*) FILTER (WHERE heat_cost IS NULL OR heat_cost <= 0) AS bad_heat_cost,
       count(*) FILTER (WHERE rain_cost IS NULL OR rain_cost <= 0) AS bad_rain_cost,
       count(*) FILTER (WHERE cost IS NULL OR cost <= 0) AS bad_cost,
       count(*) FILTER (WHERE safety_cost IS NOT NULL OR safety_score IS NOT NULL) AS non_null_safety
FROM ways;

\echo '== 시연 지점 최근접 꼭짓점과 연결 컴포넌트'
CREATE TEMP TABLE demo AS
SELECT
    (SELECT id FROM ways_vertices_pgr
     ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(:slng, :slat), 4326) LIMIT 1) AS start_vid,
    (SELECT id FROM ways_vertices_pgr
     ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(:elng, :elat), 4326) LIMIT 1) AS end_vid;

CREATE TEMP TABLE comp AS
SELECT node, component FROM pgr_connectedComponents('SELECT gid AS id, source, target, cost, reverse_cost FROM ways');

SELECT d.start_vid,
       round(ST_Distance(vs.the_geom::geography, ST_SetSRID(ST_MakePoint(:slng, :slat), 4326)::geography)::numeric, 1) AS start_snap_m,
       d.end_vid,
       round(ST_Distance(ve.the_geom::geography, ST_SetSRID(ST_MakePoint(:elng, :elat), 4326)::geography)::numeric, 1) AS end_snap_m,
       cs.component = ce.component AS same_component,
       (SELECT count(*) FROM comp WHERE component = cs.component) AS component_size,
       (SELECT count(DISTINCT component) FROM comp) AS components,
       (SELECT max(n) FROM (SELECT count(*) AS n FROM comp GROUP BY component) x) AS largest_component
FROM demo d
JOIN ways_vertices_pgr vs ON vs.id = d.start_vid
JOIN ways_vertices_pgr ve ON ve.id = d.end_vid
JOIN comp cs ON cs.node = d.start_vid
JOIN comp ce ON ce.node = d.end_vid;

\echo '== 세 모드 경로 비교 (pgr_dijkstra, directed := false)'
CREATE TEMP TABLE route AS
SELECT 'SHORTEST' AS mode, r.path_seq, r.edge
FROM demo d, pgr_dijkstra('SELECT gid AS id, source, target, cost, reverse_cost FROM ways',
                          d.start_vid, d.end_vid, directed := false) r
UNION ALL
SELECT 'HEAT', r.path_seq, r.edge
FROM demo d, pgr_dijkstra('SELECT gid AS id, source, target, heat_cost AS cost, heat_cost AS reverse_cost FROM ways',
                          d.start_vid, d.end_vid, directed := false) r
UNION ALL
SELECT 'RAIN', r.path_seq, r.edge
FROM demo d, pgr_dijkstra('SELECT gid AS id, source, target, rain_cost AS cost, rain_cost AS reverse_cost FROM ways',
                          d.start_vid, d.end_vid, directed := false) r;

SELECT r.mode,
       count(*) AS edges,
       round(sum(w.length_m)::numeric, 1) AS total_m,
       round((sum(w.length_m * w.shade_ratio) / sum(w.length_m))::numeric, 3) AS shade_ratio,
       round(COALESCE(sum(w.length_m) FILTER (WHERE w.in_flood_zone), 0)::numeric, 1) AS flood_m,
       round(COALESCE(sum(w.length_m) FILTER (WHERE w.underpass <> 'NONE'), 0)::numeric, 1) AS underpass_m,
       count(*) FILTER (WHERE w.near_shelter) AS near_shelter_edges,
       round(sum(w.heat_cost)::numeric, 1) AS heat_cost,
       round(sum(w.rain_cost)::numeric, 1) AS rain_cost
FROM route r JOIN ways w ON w.gid = r.edge
GROUP BY r.mode
ORDER BY array_position(ARRAY['SHORTEST', 'HEAT', 'RAIN'], r.mode);

\echo '== 경로끼리 겹치는 간선 수 (shared = edges_a = edges_b 이면 두 경로가 같다)'
WITH e AS (SELECT DISTINCT mode, edge FROM route WHERE edge > 0)
SELECT p.mode_a, p.mode_b,
       (SELECT count(*) FROM e WHERE mode = p.mode_a) AS edges_a,
       (SELECT count(*) FROM e WHERE mode = p.mode_b) AS edges_b,
       (SELECT count(*) FROM e x JOIN e y ON x.edge = y.edge
        WHERE x.mode = p.mode_a AND y.mode = p.mode_b) AS shared_edges
FROM (VALUES ('SHORTEST', 'HEAT'), ('SHORTEST', 'RAIN'), ('HEAT', 'RAIN')) AS p(mode_a, mode_b);
