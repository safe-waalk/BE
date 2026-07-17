-- Safe-walk 데이터베이스 스키마
-- 작업계획서(Safe-walk_백엔드_작업계획서.md) 1단계 기준
-- 실행: psql -d safewalk -f BE/db/schema.sql

-- ① 범죄 위험 구역 (crime_zones.csv)
CREATE TABLE crime_zone (
    id BIGSERIAL PRIMARY KEY,
    gid INT,                          -- 원본 구역 ID
    grade INT NOT NULL,               -- 위험 등급 0~10
    geom GEOMETRY(Point, 4326)         -- 좌표 (4326 = GPS 위경도 좌표계)
);

-- ② CCTV
CREATE TABLE cctv (
    id BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    camera_count INT,                 -- 한 지점에 카메라 여러 대일 수 있음
    geom GEOMETRY(Point, 4326)
);

-- ③ 보안등
CREATE TABLE security_light (
    id BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    geom GEOMETRY(Point, 4326)
);

-- ④ 안심벨
CREATE TABLE safety_bell (
    id BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    geom GEOMETRY(Point, 4326)
);

-- ⑤ 사용자 신고
CREATE TABLE report (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,            -- 사용자가 쓴 원문
    category VARCHAR(30),             -- LLM이 파싱한 카테고리 (LIGHTING 등)
    severity VARCHAR(10),             -- LLM이 파싱한 위험도 (HIGH/MID/LOW)
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED
    geom GEOMETRY(Point, 4326),
    created_at TIMESTAMP DEFAULT now()
);

-- 공간 인덱스 (GIST)
-- 이거 안 하면 반경 검색이 엄청 느려짐 (전체 테이블 다 뒤짐)
-- GIST 인덱스 = 좌표 전용 인덱스. "주변에 뭐 있어?" 검색을 빠르게 해줌.
CREATE INDEX idx_crime_zone_geom ON crime_zone USING GIST(geom);
CREATE INDEX idx_cctv_geom ON cctv USING GIST(geom);
CREATE INDEX idx_light_geom ON security_light USING GIST(geom);
CREATE INDEX idx_bell_geom ON safety_bell USING GIST(geom);
CREATE INDEX idx_report_geom ON report USING GIST(geom);
