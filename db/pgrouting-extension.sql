-- pgRouting 확장 활성화 (Supabase에서 1회 실행)
-- 도보망 그래프 기반 최단경로 계산(pgr_dijkstra 등)에 필요. PostGIS가 먼저 활성화되어 있어야 한다.
-- 실행: psql "host=... sslmode=require" -f db/pgrouting-extension.sql
CREATE EXTENSION IF NOT EXISTS pgrouting;
