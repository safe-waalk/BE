-- 기후 경로(폭염/호우) 기능용 스키마
-- 실행: psql -d safewalk -f db/climate/schema.sql   (load.sh가 맨 처음 실행한다)
--
-- 몇 번을 실행해도 같은 상태가 된다. 테이블을 지우지 않으므로 이미 적재된 데이터는 남는다.
-- 데이터를 비우고 다시 채우는 일은 load.sh가 한다.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgrouting;

-- ── 보행 그래프 ─────────────────────────────────────────────
-- 교차 노드에서 분할된 OSM way 조각 하나가 한 행이다.
-- the_geom에는 조각 안의 형상점이 전부 들어간다(꼭짓점 2개짜리 직선이 아니다).
CREATE TABLE IF NOT EXISTS ways (
    gid           BIGINT PRIMARY KEY,
    osm_id        BIGINT,
    source        BIGINT,
    target        BIGINT,
    highway       TEXT,
    underpass     TEXT,               -- 'NONE' | 'PEDESTRIAN'(지하보도) | 'ROAD'(지하차도)
    covered       BOOLEAN,
    name          TEXT,
    length_m      DOUBLE PRECISION,
    cost          DOUBLE PRECISION,   -- = length_m (양방향 보행)
    reverse_cost  DOUBLE PRECISION,   -- = length_m
    shade_ratio   DOUBLE PRECISION,   -- 0.0~1.0
    in_flood_zone BOOLEAN,
    near_shelter  BOOLEAN,
    heat_cost     DOUBLE PRECISION,
    rain_cost     DOUBLE PRECISION,
    safety_score  DOUBLE PRECISION,   -- NULL (SAFE 모드는 COALESCE(safety_cost, cost)로 폴백)
    safety_cost   DOUBLE PRECISION,   -- NULL
    the_geom      GEOMETRY(LineString, 4326),
    CONSTRAINT ways_underpass_check CHECK (underpass IN ('NONE', 'PEDESTRIAN', 'ROAD'))
);

-- 분할점 노드만 담는다. id는 1부터 새로 매기고 원래 OSM node id는 osm_id에 둔다.
CREATE TABLE IF NOT EXISTS ways_vertices_pgr (
    id          BIGINT PRIMARY KEY,
    osm_id      BIGINT,
    lat         DOUBLE PRECISION,
    lon         DOUBLE PRECISION,
    elevation_m DOUBLE PRECISION,
    the_geom    GEOMETRY(Point, 4326)
);

-- ── 기후 레이어 ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cooling_shelter (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT,
    facility_type TEXT,
    address       TEXT,
    open_time     TEXT,
    close_time    TEXT,
    source        TEXT,
    geom          GEOMETRY(Point, 4326)
);

CREATE TABLE IF NOT EXISTS flood_trace (
    id     BIGSERIAL PRIMARY KEY,
    year   INT,
    source TEXT,
    geom   GEOMETRY(MultiPolygon, 4326)
);

CREATE INDEX IF NOT EXISTS idx_ways_the_geom              ON ways              USING GIST (the_geom);
CREATE INDEX IF NOT EXISTS idx_ways_source                ON ways              (source);
CREATE INDEX IF NOT EXISTS idx_ways_target                ON ways              (target);
CREATE INDEX IF NOT EXISTS idx_ways_vertices_pgr_the_geom ON ways_vertices_pgr USING GIST (the_geom);
CREATE INDEX IF NOT EXISTS idx_cooling_shelter_geom       ON cooling_shelter   USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_flood_trace_geom           ON flood_trace       USING GIST (geom);

-- ── 기존 안전 레이어 (db/schema.sql과 같은 DDL을 재실행 가능하게) ──────────
-- db/schema.sql은 IF NOT EXISTS가 없어 두 번 실행하면 실패하므로 여기서 같은 정의를 다시 만든다.
-- 기존 SAFE 모드와 /api/safety/layers가 죽지 않도록 빈 테이블로라도 있어야 한다.
CREATE TABLE IF NOT EXISTS crime_zone (
    id    BIGSERIAL PRIMARY KEY,
    gid   INT,
    grade INT NOT NULL,
    geom  GEOMETRY(Point, 4326)
);

CREATE TABLE IF NOT EXISTS cctv (
    id           BIGSERIAL PRIMARY KEY,
    address      VARCHAR(300),
    camera_count INT,
    geom         GEOMETRY(Point, 4326)
);

CREATE TABLE IF NOT EXISTS security_light (
    id      BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    geom    GEOMETRY(Point, 4326)
);

CREATE TABLE IF NOT EXISTS safety_bell (
    id      BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    geom    GEOMETRY(Point, 4326)
);

CREATE TABLE IF NOT EXISTS report (
    id         BIGSERIAL PRIMARY KEY,
    content    TEXT NOT NULL,
    category   VARCHAR(30),
    severity   VARCHAR(10),
    status     VARCHAR(20) DEFAULT 'PENDING',
    geom       GEOMETRY(Point, 4326),
    created_at TIMESTAMP DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_crime_zone_geom ON crime_zone     USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_cctv_geom       ON cctv           USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_light_geom      ON security_light USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_bell_geom       ON safety_bell    USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_report_geom     ON report         USING GIST (geom);
