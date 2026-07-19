-- pgr_dijkstra 동작 검증: 서울시청 인근 두 지점 사이 경로 계산
-- 실행: psql "host=... sslmode=require" -f db/verify-walk-network.sql
SELECT * FROM pgr_dijkstra(
  'SELECT gid AS id, source, target, cost, reverse_cost FROM ways',
  (SELECT id FROM ways_vertices_pgr ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326) LIMIT 1),
  (SELECT id FROM ways_vertices_pgr ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(126.9700, 37.5600), 4326) LIMIT 1),
  directed := false
);
