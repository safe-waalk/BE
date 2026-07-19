# 서울 도보망 데이터 적재 (pgRouting) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **이 플랜은 일반적인 Spring Boot 기능 플랜과 다르다.** Java 코드가 없고, Docker/psql로 실행하는 1회성 데이터 적재 절차다. "테스트"는 JUnit이 아니라 각 단계 뒤에 실행하는 검증 SQL/명령이다.

**Goal:** 서울 지역 OSM 도보 가능 도로망을 Supabase(PostGIS + pgRouting)에 그래프(노드+엣지)로 적재하고, `pgr_dijkstra`로 실제 경로가 계산되는지 검증한다.

**Architecture:** Docker 컨테이너(`iboates/osmium`, `iboates/osm2pgrouting`)로 OSM 데이터를 다운로드·클리핑·적재한다. 별도 서버나 애플리케이션 코드 없이, Supabase DB 안에 `ways`/`ways_vertices_pgr` 테이블이 생기는 것이 최종 산출물이다. 재현 가능하도록 각 단계를 셸 스크립트로 저장소에 커밋한다.

**Tech Stack:** Docker, `iboates/osmium` (OSM 클리핑), `iboates/osm2pgrouting` (그래프 적재), PostgreSQL `psql` (검증), Supabase PostGIS + pgRouting.

## Global Constraints

- 대상 지역(서울 bbox, osmium 포맷 `left,bottom,right,top` = `minlon,minlat,maxlon,maxlat`): `126.76,37.42,127.18,37.70`
- 데이터 소스: `https://download.geofabrik.de/asia/south-korea-latest.osm.pbf` (약 261MB)
- 필터링: 기본 설정 (자동차 전용도로만 제외, 나머지는 전부 도보 가능으로 취급) — `osm2pgrouting` 커스텀 설정 파일 없이 기본값 사용
- 결과 테이블명: `ways`(엣지), `ways_vertices_pgr`(노드) — `osm2pgrouting` 기본 명명 그대로 사용
- 다운로드/클리핑한 `.osm.pbf` 파일은 git에 커밋하지 않는다 (`osm-data/`를 `.gitignore`에 추가)
- Supabase는 SSL 연결 필수 — DB 접속 스크립트에는 `PGSSLMODE=require` 환경변수를 넘긴다
- DB 접속 정보는 기존 `.env`(`SUPABASE_DB_HOST`/`PORT`/`NAME`/`USER`/`PASSWORD`)를 재사용한다
- 참고 스펙: `docs/superpowers/specs/2026-07-19-osm-walk-network-design.md`

---

### Task 1: pgRouting 확장 활성화

**Files:**
- Create: `db/pgrouting-extension.sql`

**Interfaces:**
- Produces: Supabase DB에 `pgrouting` PostgreSQL 확장이 활성화된 상태 — Task 3(적재)이 이 확장의 `pgr_dijkstra` 등 함수에 의존, Task 4(검증)도 마찬가지.

- [ ] **Step 1: 확장 활성화 SQL 파일 작성**

`db/pgrouting-extension.sql`:

```sql
-- pgRouting 확장 활성화 (Supabase에서 1회 실행)
-- 도보망 그래프 기반 최단경로 계산(pgr_dijkstra 등)에 필요. PostGIS가 먼저 활성화되어 있어야 한다.
-- 실행: psql "host=... sslmode=require" -f db/pgrouting-extension.sql
CREATE EXTENSION IF NOT EXISTS pgrouting;
```

- [ ] **Step 2: 실행**

Run (bash, `.env` 로드 후):
```bash
source .env
export PGPASSWORD="$SUPABASE_DB_PASSWORD"
psql "host=$SUPABASE_DB_HOST port=$SUPABASE_DB_PORT dbname=$SUPABASE_DB_NAME user=$SUPABASE_DB_USER sslmode=require" -f db/pgrouting-extension.sql
```
Expected: `CREATE EXTENSION` 출력 (이미 있으면 `NOTICE: extension "pgrouting" already exists, skipping`)

- [ ] **Step 3: 검증**

Run:
```bash
psql "host=$SUPABASE_DB_HOST port=$SUPABASE_DB_PORT dbname=$SUPABASE_DB_NAME user=$SUPABASE_DB_USER sslmode=require" -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'pgrouting';"
```
Expected: `pgrouting` 행 1개 반환

- [ ] **Step 4: 커밋**

```bash
git add db/pgrouting-extension.sql
git commit -m "feat: add pgRouting extension activation script"
```

---

### Task 2: 서울 OSM 도보망 데이터 다운로드 + 클리핑

**Files:**
- Modify: `.gitignore`
- Create: `db/download-seoul-osm.sh`

**Interfaces:**
- Produces: `osm-data/seoul.osm.pbf` 파일 (git에는 커밋 안 됨) — Task 3이 이 파일을 입력으로 사용.

- [ ] **Step 1: Docker 동작 확인**

Run: `docker info`
Expected: 에러 없이 Docker 정보 출력 (데몬이 안 떠 있으면 Docker Desktop을 먼저 실행)

- [ ] **Step 2: `.gitignore`에 데이터 디렉터리 추가**

`.gitignore`에 다음 줄 추가:

```
osm-data/
```

- [ ] **Step 3: 다운로드+클리핑 스크립트 작성**

`db/download-seoul-osm.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

mkdir -p osm-data

if [ ! -f osm-data/south-korea-latest.osm.pbf ]; then
  echo "Downloading South Korea OSM extract..."
  curl -L -o osm-data/south-korea-latest.osm.pbf \
    https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
fi

echo "Clipping to Seoul bounding box..."
docker run --rm -v "$(pwd)/osm-data:/data" iboates/osmium:latest \
  osmium extract --bbox=126.76,37.42,127.18,37.70 \
  -o /data/seoul.osm.pbf --overwrite \
  /data/south-korea-latest.osm.pbf

echo "Done: osm-data/seoul.osm.pbf"
ls -lh osm-data/seoul.osm.pbf
```

- [ ] **Step 4: 실행**

Run:
```bash
chmod +x db/download-seoul-osm.sh
./db/download-seoul-osm.sh
```
Expected: `osm-data/seoul.osm.pbf` 생성, 원본(261MB)보다 훨씬 작은 크기로 출력됨 (서울만 추출했으므로)

- [ ] **Step 5: 검증**

Run: `ls -la osm-data/seoul.osm.pbf`
Expected: 파일이 존재하고 크기가 0바이트가 아님

- [ ] **Step 6: 커밋**

```bash
git add .gitignore db/download-seoul-osm.sh
git commit -m "feat: add script to download and clip Seoul OSM extract"
```

---

### Task 3: osm2pgrouting로 Supabase에 적재

**Files:**
- Create: `db/load-osm-walk-network.sh`

**Interfaces:**
- Consumes: `osm-data/seoul.osm.pbf` (Task 2)
- Produces: Supabase DB에 `ways`, `ways_vertices_pgr` 테이블 — Task 4가 이 테이블들을 쿼리한다.

- [ ] **Step 1: 적재 스크립트 작성**

`db/load-osm-walk-network.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

source .env

docker run --rm -v "$(pwd)/osm-data:/data" \
  -e PGPASSWORD="$SUPABASE_DB_PASSWORD" \
  -e PGSSLMODE=require \
  iboates/osm2pgrouting:latest \
  --dbname "$SUPABASE_DB_NAME" \
  --username "$SUPABASE_DB_USER" \
  --host "$SUPABASE_DB_HOST" \
  --port "$SUPABASE_DB_PORT" \
  --clean \
  --f /data/seoul.osm.pbf
```

- [ ] **Step 2: 실행**

Run:
```bash
chmod +x db/load-osm-walk-network.sh
./db/load-osm-walk-network.sh
```
Expected: 에러 없이 종료, "ways", "ways_vertices_pgr" 등 테이블 생성/적재 로그 출력

- [ ] **Step 3: 검증**

Run:
```bash
psql "host=$SUPABASE_DB_HOST port=$SUPABASE_DB_PORT dbname=$SUPABASE_DB_NAME user=$SUPABASE_DB_USER sslmode=require" \
  -c "SELECT (SELECT COUNT(*) FROM ways) AS edges, (SELECT COUNT(*) FROM ways_vertices_pgr) AS nodes;"
```
Expected: `edges`, `nodes` 둘 다 0보다 훨씬 큰 값 (서울 지역 규모면 최소 수만 단위)

- [ ] **Step 4: 커밋**

```bash
git add db/load-osm-walk-network.sh
git commit -m "feat: add script to load Seoul OSM data into pgRouting tables"
```

---

### Task 4: `pgr_dijkstra` 샘플 쿼리 검증

**Files:**
- Create: `db/verify-walk-network.sql`

**Interfaces:**
- Consumes: `ways`, `ways_vertices_pgr` (Task 3)

- [ ] **Step 1: 검증 쿼리 작성**

`db/verify-walk-network.sql`:

```sql
-- pgr_dijkstra 동작 검증: 서울시청 인근 두 지점 사이 경로 계산
-- 실행: psql "host=... sslmode=require" -f db/verify-walk-network.sql
SELECT * FROM pgr_dijkstra(
  'SELECT gid AS id, source, target, cost, reverse_cost FROM ways',
  (SELECT id FROM ways_vertices_pgr ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326) LIMIT 1),
  (SELECT id FROM ways_vertices_pgr ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(126.9700, 37.5600), 4326) LIMIT 1),
  directed := false
);
```

- [ ] **Step 2: 실행**

Run:
```bash
psql "host=$SUPABASE_DB_HOST port=$SUPABASE_DB_PORT dbname=$SUPABASE_DB_NAME user=$SUPABASE_DB_USER sslmode=require" -f db/verify-walk-network.sql
```
Expected: 빈 결과가 아니라, `seq`/`node`/`edge`/`cost`/`agg_cost` 컬럼을 가진 여러 행이 반환됨 (경로가 여러 엣지로 구성됨을 의미)

- [ ] **Step 3: 결과 확인**

반환된 행들의 `node` 값을 따라가 보면 시작 정점에서 도착 정점까지 이어지는지 확인. 마지막 행의 `edge`가 `-1`이면 정상 종료(도착 지점 도달)를 의미한다.

- [ ] **Step 4: 커밋**

```bash
git add db/verify-walk-network.sql
git commit -m "test: add pgr_dijkstra verification query for Seoul walk network"
```
