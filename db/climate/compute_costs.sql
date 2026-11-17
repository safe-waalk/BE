-- 그늘 비율·침수/쉼터 플래그·폭염/호우 비용 계산. load.sh가 --single-transaction으로 실행한다.
-- 입력: ways, flood_trace, cooling_shelter, climate_work.shade_src
-- 계수를 바꾸면 db/climate/README.md의 비용 공식도 같이 고친다.

-- ── 그늘 공급원 (EPSG:5186, 미터) ─────────────────────────────
-- building: 바닥 면 + 높이 비례 버퍼(build_graph.py가 buffer_m = min(높이 x 0.5, 15m)로 정함)
-- green   : natural=wood / landuse=forest / leisure=park 면 그대로
-- tree    : natural=tree 점 + 반경 3.5m,  tree_row: 선 + 3.5m
DROP TABLE IF EXISTS climate_work.shade;
CREATE TABLE climate_work.shade AS
SELECT kind, osm_type, osm_id, geom
FROM (
    SELECT kind, osm_type, osm_id,
           CASE
               WHEN kind = 'building' THEN
                   ST_Buffer(ST_MakeValid(ST_BuildArea(ST_Transform(geom, 5186))), buffer_m, 'quad_segs=4')
               WHEN kind = 'green' THEN
                   ST_MakeValid(ST_BuildArea(ST_Transform(geom, 5186)))
               ELSE
                   ST_Buffer(ST_Transform(geom, 5186), buffer_m, 'quad_segs=4')
           END AS geom
    FROM climate_work.shade_src
) s
WHERE geom IS NOT NULL AND NOT ST_IsEmpty(geom);

CREATE INDEX ON climate_work.shade USING GIST (geom);
ANALYZE climate_work.shade;

DROP TABLE IF EXISTS climate_work.edge5186;
CREATE TABLE climate_work.edge5186 AS
SELECT gid, ST_Transform(the_geom, 5186) AS geom FROM ways;
CREATE INDEX ON climate_work.edge5186 USING GIST (geom);
ANALYZE climate_work.edge5186;

-- ── shade_ratio = (간선 ∩ 그늘 합집합 길이) / 간선 길이 ─────────
-- 간선과 겹치는 그늘 면마다 교차 선분을 구한 뒤 그 선분들을 ST_Union으로 녹이면
-- 겹친 그늘이 두 번 세어지지 않는다. 분모도 같은 5186 길이를 쓴다(이 지역 축척 오차 < 0.01%).
UPDATE ways SET shade_ratio = 0.0;

WITH covered AS (
    SELECT e.gid,
           ST_Length(ST_Union(ST_Intersection(e.geom, s.geom))) AS shaded_m,
           ST_Length(e.geom) AS len_m
    FROM climate_work.edge5186 e
    JOIN climate_work.shade s ON ST_Intersects(e.geom, s.geom)
    GROUP BY e.gid, e.geom
)
UPDATE ways w
SET shade_ratio = LEAST(1.0, GREATEST(0.0, c.shaded_m / NULLIF(c.len_m, 0)))
FROM covered c
WHERE w.gid = c.gid AND c.len_m > 0;

-- 지하보도와 지붕 있는 길은 그늘 100%
UPDATE ways SET shade_ratio = 1.0 WHERE underpass = 'PEDESTRIAN' OR covered;

-- ── 침수흔적·쉼터 플래그 ──────────────────────────────────────
-- 침수흔적은 침수된 필지·건물 폴리곤이라 도로 중심선과 거의 교차하지 않는다(교차 기준이면 5,703개 중 17개).
-- 그래서 흔적에서 flood_near_m 미터 이내를 지나는 간선을 침수 이력 구간으로 본다. 0이면 교차 기준과 같다.
\if :{?flood_near_m}
\else
\set flood_near_m 10
\endif
UPDATE ways w
SET in_flood_zone = EXISTS (
    SELECT 1 FROM flood_trace f
    WHERE ST_DWithin(w.the_geom::geography, f.geom::geography, :flood_near_m)
);

UPDATE ways w
SET near_shelter = EXISTS (
    SELECT 1 FROM cooling_shelter s
    WHERE ST_DWithin(w.the_geom::geography, s.geom::geography, 50)
);

-- ── 비용 ─────────────────────────────────────────────────────
-- heat_cost = length_m * (1 + 1.5 * (1 - shade_ratio)) * (near_shelter ? 0.85 : 1.0)
-- rain_cost = length_m * (in_flood_zone ? 8 : 1) * (underpass <> 'NONE' ? 30 : 1)
UPDATE ways
SET heat_cost = length_m * (1 + 1.5 * (1 - shade_ratio))
                         * CASE WHEN near_shelter THEN 0.85 ELSE 1.0 END,
    rain_cost = length_m * CASE WHEN in_flood_zone THEN 8 ELSE 1 END
                         * CASE WHEN underpass <> 'NONE' THEN 30 ELSE 1 END,
    safety_score = NULL,
    safety_cost = NULL;

-- pgRouting이 깨지지 않도록 NULL·0 이하 비용이 하나라도 있으면 전체를 되돌린다.
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM ways
    WHERE cost IS NULL OR cost <= 0 OR reverse_cost IS NULL OR reverse_cost <= 0
       OR heat_cost IS NULL OR heat_cost <= 0 OR rain_cost IS NULL OR rain_cost <= 0
       OR shade_ratio IS NULL OR shade_ratio < 0 OR shade_ratio > 1
       OR in_flood_zone IS NULL OR near_shelter IS NULL OR underpass IS NULL;
    IF bad > 0 THEN
        RAISE EXCEPTION '비용/플래그가 비었거나 범위를 벗어난 간선 %개', bad;
    END IF;
END $$;

ANALYZE ways;
ANALYZE ways_vertices_pgr;
ANALYZE flood_trace;
ANALYZE cooling_shelter;
