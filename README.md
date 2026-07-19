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

| Method | Endpoint | 설명 |
|---|---|---|
| GET | `/api/safety/summary?lat={}&lng={}` | 좌표 기준 반경 내 CCTV/보안등/안심벨/범죄주의구역 집계(개수, 최근접거리) |
| GET | `/api/safety/layers?swLat={}&swLng={}&neLat={}&neLng={}&layers={csv}` | 지도 bounds(남서/북동) 안의 CCTV/보안등/안심벨/범죄주의구역 개별 좌표 조회 |
| GET | `/api/safety/score?lat={}&lng={}` | 좌표 기준 안전점수(0~100)와 세부 내역(범죄 감점, CCTV/보안등 가점) |
| POST | `/api/reports` | 사용자 신고 등록 |
| GET | `/api/reports?swLat={}&swLng={}&neLat={}&neLng={}` | bounds 안의 신고 목록 조회 |
| GET | `/api/health/db` | DB 연결 상태 확인 |
| GET | `/api/health/db/counts` | 테이블별 row 카운트 확인 |

각 엔드포인트의 정확한 요청/응답 계약은 `docs/superpowers/specs/`의 해당 설계 문서를 참고.

## DB 스키마

`db/schema.sql` 참고. 주요 테이블: `crime_zone`, `cctv`, `security_light`, `safety_bell`, `report`. 모든 위치 데이터는 `geom GEOMETRY(Point, 4326)` 컬럼에 저장되며, 반경/bounds 검색을 위해 `GIST` 공간 인덱스가 걸려 있다.

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
