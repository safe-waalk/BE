# Safe-walk Backend

지도 기반 안전 보행 서비스의 백엔드. Supabase(PostgreSQL + PostGIS)에 적재된 CCTV/보안등/안심벨/범죄주의구역/사용자 신고 데이터를 기반으로 공간 조회 및 안전점수 API를 제공한다.

## 기술 스택

- Java 21, Spring Boot 4.0.7 (`spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`)
- PostgreSQL + PostGIS (Supabase)
- `NamedParameterJdbcTemplate` 직접 사용 — ORM(JPA/Hibernate Spatial) 도입 안 함
- JUnit 5 + AssertJ + Mockito

## 시작하기

### 1. 환경변수 설정

`.env` 파일을 프로젝트 루트에 만들고 다음 값을 채운다 (Supabase 프로젝트 접속 정보):

```
SUPABASE_DB_HOST=
SUPABASE_DB_PORT=
SUPABASE_DB_NAME=
SUPABASE_DB_USER=
SUPABASE_DB_PASSWORD=
```

### 2. 실행

```bash
./gradlew bootRun
```

또는 Docker로:

```bash
docker compose up --build
```

기본 포트는 `8080`.

### 3. 테스트

```bash
./gradlew test
```

일부 테스트(`*IntegrationTest`)는 실제 Supabase DB에 연결한다 — `.env` 설정이 필요하다.

## API

FE를 포함한 외부 소비자를 위한 API 계약은 이 문서를 기준으로 한다 (스펙 원본은 `docs/superpowers/specs/`, 최신 계약은 이 표가 우선).

### 구현 상태 (2026-07-19 기준)

| Method | Endpoint | 설명 | 상태 |
|---|---|---|---|
| GET | `/api/safety/summary` | 좌표 기준 반경 내 안전 인프라 집계 | `main` |
| GET | `/api/safety/layers` | 지도 bounds 안의 개별 좌표 조회 | `main` |
| GET | `/api/safety/score` | 좌표 기준 안전점수(0~100) | `main` |
| POST/GET | `/api/reports` | 신고 등록/조회 | `main` |
| GET | `/api/health/db`, `/api/health/db/counts` | 헬스체크 | `main` |

### CORS ⚠️

BE에 CORS 설정이 없다. 로컬 프론트 개발은 Vite dev proxy 등으로 우회한다 (`/api` → `http://localhost:8080`). 배포 시 같은 도메인에서 서빙하거나 CORS 추가가 필요해지면 그때 논의.

### 에러 응답 공통

- 400: `ResponseStatusException` 기반 Spring 기본 포맷 (`{"timestamp","status","error","path",...}`). 별도 에러 코드 체계 없음
- 500: DB 오류 등 — 별도 핸들링 없이 Spring 기본 응답

---

### `GET /api/safety/summary?lat={}&lng={}`

좌표 1개 기준 타입별 고정 반경 내 집계.

- `lat`(-90~90), `lng`(-180~180) 필수, 범위 밖/누락 시 400
- 반경 고정: cctv/crimeZone 150m, securityLight/safetyBell 100m

```json
{
  "cctv":          { "count": 12, "nearestDistance": 45.2 },
  "securityLight": { "count": 8,  "nearestDistance": 20.1 },
  "safetyBell":    { "count": 1,  "nearestDistance": 180.4 },
  "crimeZone":     { "count": 2,  "nearestDistance": 60.0, "maxGrade": 7 }
}
```

`nearestDistance`는 미터, 반경 내 데이터 없으면 `null` (count는 0).

### `GET /api/safety/layers?swLat={}&swLng={}&neLat={}&neLng={}&layers={csv}`

지도 bounds(남서/북동) 안의 안전 인프라 개별 좌표를 레이어별로 반환.

- `swLat/swLng`/`neLat/neLng` 필수 — 카카오맵 `bounds.getSouthWest()`/`getNorthEast()`와 1:1로 대응
- `layers`: `cctv`, `securityLight`, `safetyBell`, `crimeZone` 중 1개 이상 콤마 구분. 누락/빈 값/오타 → 400
- **레이어당 최대 500건** — 초과분은 잘리며 응답에 표시 없음 (클라이언트가 줌인 유도로 대응)
- **bounds 한 변이 5km 초과 시 400** — 위도 1도 ≈ 111,320m, 경도는 평균 위도 코사인 보정
- 응답은 **요청한 레이어 키만 포함** (요청 안 한 레이어는 키 자체가 없음)

```json
{
  "cctv":      [{ "id": 1, "lat": 37.5665, "lng": 126.978, "address": "...", "cameraCount": 2 }],
  "crimeZone": [{ "id": 3, "lat": 37.555,  "lng": 126.97,  "grade": 7 }]
}
```

`crimeZone.grade`는 0~10.

### `GET /api/safety/score?lat={}&lng={}`

좌표 기준 안전점수. 기존 `summary` 데이터를 재사용해 계산하며 새 DB 쿼리는 추가하지 않는다.

- `lat`/`lng` 필수, 범위 밖/누락 시 400
- 산식: `score = round(clamp(70 - crimePenalty + cctvBonus + lightBonus, 0, 100))` — 상세 상수는 `docs/superpowers/specs/2026-07-19-safety-score-design.md` 참고
- `report_penalty`/`night_time_penalty`는 아직 반영 안 함 (신고 채택 플로우·시간대 유의미성 부재로 제외)

```json
{"score": 62, "crimePenalty": 17.5, "cctvBonus": 20.0, "lightBonus": 10.0}
```

`crimePenalty`/`cctvBonus`/`lightBonus`는 반올림하지 않은 소수 (디버깅/투명성 목적).

### `POST` / `GET /api/reports`

```
POST /api/reports
{"content": "...", "lat": 37.5665, "lng": 126.9780, "category": "LIGHTING", "severity": "MEDIUM"}
→ 201, ReportResponse 반환

GET /api/reports
→ 200, 전체 신고 목록 배열 반환 (bounds 필터 없음)
```

- `content`, `lat`, `lng` 필수. `category`/`severity`는 선택이며, 값이 있을 때만 화이트리스트 검증
  - `category`: `LIGHTING`/`CCTV`/`SUSPICIOUS_AREA`/`ROAD_ENVIRONMENT`/`CRIME_RISK`/`NOISE_GROUP`/`WOMEN_SAFETY`/`FALSE_REPORT`/`PRIVACY_RISK`/`ETC`
  - `severity`: `HIGH`/`MEDIUM`/`LOW`
- `status`는 서버가 항상 `PENDING`으로 저장, 관리자 검토 기능은 아직 없음
- `GET`은 bounds 파라미터가 없다 — 전체 목록을 반환 (지도 bounds 조회가 필요해지면 별도 논의)

```json
{"id": 1, "content": "...", "category": "LIGHTING", "severity": "MEDIUM", "status": "PENDING", "lat": 37.5665, "lng": 126.9780, "createdAt": "2026-07-19T18:50:00"}
```

### `GET /api/health/db`, `GET /api/health/db/counts`

DB 연결/테이블별 row 수 확인용. 외부 소비자는 BE 연결 확인 용도로만 사용.

## DB 스키마

`db/schema.sql` 참고. 주요 테이블: `crime_zone`, `cctv`, `security_light`, `safety_bell`, `report`. 모든 위치 데이터는 `geom GEOMETRY(Point, 4326)` 컬럼에 저장되며, 반경/bounds 검색을 위해 `GIST` 공간 인덱스가 걸려 있다.

## 도보망(pgRouting) 데이터

서울 도보 최단경로 계산(`pgr_dijkstra`)을 위한 도로망 그래프(`ways`, `ways_vertices_pgr` 테이블)는 OSM(OpenStreetMap) 데이터를 가공해 Supabase에 적재해뒀다. **이미 공용 Supabase DB에 들어가 있으므로, 조회만 할 거면 아래 재적재 과정은 필요 없다** — `.env`에 같은 Supabase 접속 정보만 있으면 바로 `pgr_dijkstra` 쿼리를 쓸 수 있다.

### 재적재가 필요한 경우 (스키마 변경, 다른 지역 확장 등)

사전 준비: Docker Desktop, `.env`(Supabase 접속 정보).

```bash
cd BE/db

# 1. pgRouting 확장 활성화 (최초 1회)
psql "host=$SUPABASE_DB_HOST port=$SUPABASE_DB_PORT dbname=$SUPABASE_DB_NAME user=$SUPABASE_DB_USER sslmode=require" -f pgrouting-extension.sql

# 2. 전국 OSM → 서울 bbox로 클리핑 (osm-data/seoul.osm.pbf 생성)
./download-seoul-osm.sh

# 3. PBF → XML 변환 후 Supabase에 적재 (osm-data/, ways/ways_vertices_pgr 테이블)
./load-osm-walk-network.sh

# 4. 동작 검증 (서울시청 인근 두 지점 최단경로)
psql "host=$SUPABASE_DB_HOST port=$SUPABASE_DB_PORT dbname=$SUPABASE_DB_NAME user=$SUPABASE_DB_USER sslmode=require" -f verify-walk-network.sql
```

- 2, 3단계는 `iboates/osmium`, `iboates/osm2pgrouting` **공개 Docker 이미지**를 그때그때 pull해서 실행한다(`docker run --rm`) — 우리가 별도로 빌드/push하는 이미지는 없다. Docker Desktop만 설치돼 있으면 누구나 동일하게 재현 가능.
- `osm-data/`(원본·중간 OSM 파일, 수백MB)는 `.gitignore`에 걸려 있어 저장소에는 없다. 2단계 스크립트가 geofabrik에서 다시 받아오므로 별도 공유 불필요(다운로드+클리핑에 다소 시간 소요).
- 트러블슈팅 이력(Windows Git Bash 경로 변환 이슈, `osm2pgrouting` 청크 사이즈 등)은 `load-osm-walk-network.sh` 내 주석 참고.

## 프로젝트 구성

```
src/main/java/com/safewalk/
├─ HealthController.java
├─ safety/       — 안전 인프라 조회 (summary/layers/score)
│  └─ dto/
└─ report/       — 사용자 신고 등록/조회
   └─ dto/
```

패키지 하나당 도메인 하나. 컨트롤러가 검증을, 서비스가 `NamedParameterJdbcTemplate` 쿼리를 담당하는 패턴을 따른다.

## 개발 워크플로우

이 저장소는 기능 단위로 `docs/superpowers/specs/`(설계 문서)와 `docs/superpowers/plans/`(구현 계획)를 먼저 작성한 뒤 구현하는 방식을 따른다. 새 기능을 시작하기 전에 관련 스펙/계획 문서가 있는지 먼저 확인할 것.

커밋 메시지 규칙은 `CLAUDE.md` 참고.
