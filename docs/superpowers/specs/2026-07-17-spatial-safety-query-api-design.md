# 공간 조회 API (Safety Summary) 설계

- **작성일**: 2026-07-17
- **관련 문서**: `safewalk_finetuning_project_plan.md` (Desktop), `db/schema.sql`
- **상태**: 승인됨

## 배경 / 목적

Supabase(PostgreSQL + PostGIS)에 CCTV, 보안등, 안심벨, 관공서, 범죄주의구역 데이터가 이미 적재되어 있다. 지금까지는 `HealthController`로 단순 카운트만 확인했고, 실제 좌표 기반 공간 쿼리(반경 내 개수, 최근접 거리)는 없다.

이 스펙은 좌표 하나를 입력받아 주변 안전 인프라를 요약해 반환하는 최소 API를 정의한다. 안전점수 산식(가중치 계산, 세그먼트 분석)은 이번 범위에서 제외한다 — 이 API는 향후 안전점수 기능이나 지도 표시 기능에서 재사용할 수 있는 기반 블록으로만 만든다.

## 범위

**포함**:
- 좌표 1개 기준 반경 내 안전 인프라 요약 조회 API
- 대상 테이블: `cctv`, `security_light`, `safety_bell`, `public_office`, `crime_zone`

**제외 (YAGNI)**:
- 안전점수 계산/가중치 로직
- 지도 bounds 기반 레이어 조회 (`/api/safety/layers`) — 별도 스펙에서 다룸
- 세그먼트 단위 배치 조회 (경로 분석용) — OSRM 연동 이후 별도 스펙

## 아키텍처

새 패키지 `com.safewalk.safety`에 클래스 2개만 추가한다. `HealthController`가 이미 쓰고 있는 `JdbcTemplate` 직접 사용 패턴을 그대로 따른다 (JPA/Hibernate Spatial 도입 안 함 — `build.gradle`이 의도적으로 JPA 없이 `spring-boot-starter-jdbc`만 쓰는 구성이라 이 하나의 API 때문에 ORM 계층을 새로 얹는 것은 불필요한 복잡도).

```
com.safewalk.safety/
├─ SafetyController.java   (엔드포인트)
├─ SafetyQueryService.java (JdbcTemplate로 5개 테이블 쿼리 + DTO 조립)
└─ dto/
   ├─ SafetySummaryResponse.java
   └─ InfraSummary.java (count, nearestDistance[, maxGrade])
```

별도 Repository 계층은 두지 않는다 (현재 코드베이스 규모에서 과도한 계층화).

## 엔드포인트 계약

```
GET /api/safety/summary?lat={lat}&lng={lng}
```

**파라미터**:
- `lat` (required, double, -90~90)
- `lng` (required, double, -180~180)

**응답 (200)**:
```json
{
  "cctv":          { "count": 12, "nearestDistance": 45.2 },
  "securityLight": { "count": 8,  "nearestDistance": 20.1 },
  "safetyBell":    { "count": 1,  "nearestDistance": 180.4 },
  "publicOffice":  { "count": 0,  "nearestDistance": null },
  "crimeZone":     { "count": 2,  "nearestDistance": 60.0, "maxGrade": 7 }
}
```

`nearestDistance`는 미터 단위, 반경 내 데이터가 없으면 `null`. `crimeZone`만 `maxGrade`(0~10) 필드를 추가로 가진다.

**타입별 고정 반경** (서버 상수, `SafetyQueryService` 내 정의):

| 타입 | 반경 |
|---|---|
| cctv | 150m |
| securityLight | 100m |
| safetyBell | 100m |
| publicOffice | 300m |
| crimeZone | 150m |

반경 값은 기획서 10.3 가중치 표를 참고한 초기값이며, 상수로 관리하므로 추후 조정이 쉽다.

## 데이터 흐름

1. `SafetyController`가 `lat`/`lng` 파라미터를 받아 범위 검증 (필수, -90~90 / -180~180)
2. `SafetyQueryService`가 PostGIS 포인트를 1회 생성: `ST_SetSRID(ST_MakePoint(:lng,:lat), 4326)::geography`
3. 테이블당 쿼리 1개씩 총 5개 실행 (미터 단위 계산을 위해 `geom::geography` 캐스팅):
   ```sql
   SELECT COUNT(*) AS cnt, MIN(ST_Distance(geom::geography, :point)) AS nearest
   FROM cctv
   WHERE ST_DWithin(geom::geography, :point, :radius)
   ```
   `crime_zone`은 `MAX(grade) AS max_grade` 컬럼 추가.
4. 5개 결과를 `SafetySummaryResponse`로 조립해 반환.

## 에러 처리

- `lat`/`lng` 누락 또는 범위 밖 → `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)`로 400 반환. 전역 예외 핸들러(`global/exception`)는 아직 없으므로 이번 스펙에서는 새로 만들지 않고 컨트롤러/서비스에서 직접 던진다.
- DB 쿼리 실패 시 별도 처리 없이 Spring 기본 500 응답에 위임 (현재 범위에서 별도 핸들링 불필요).

## 테스트 전략

- `@WebMvcTest` 수준: 잘못된/누락된 `lat`,`lng` → 400 검증 (DB 없이 컨트롤러 검증 로직만)
- 실제 쿼리 검증: Supabase에 이미 적재된 실데이터로 통합 테스트 + `HealthController`처럼 수동 curl 확인
- 세부 테스트 케이스는 구현 단계(TDD)에서 작성

## 미해결/향후 과제 (이번 범위 아님)

- 안전점수 산식 (스코어링 로직 자체를 이번 프로젝트 방향에서 제외하기로 함 — 별도 논의 필요)
- 지도 bounds 기반 레이어 조회 엔드포인트
- `public_office` 테이블 데이터 미적재 상태 (0건) — 이 API는 스키마 기준으로 동작하므로 영향 없음, 데이터 적재는 별도 트랙
