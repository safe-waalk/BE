# Route API 설계 문서

**날짜:** 2026-07-21  
**범위:** BE — 안전우선/최단거리 경로탐색 API

---

## 1. 목표

서울 도보망(419k OSM edges, pgRouting)을 기반으로 두 가지 모드의 경로탐색 API를 제공한다.

- **SHORTEST**: 순수 거리 기반 최단경로
- **SAFE**: 안전점수를 반영한 가중치 기반 경로 (더 돌아가더라도 CCTV·보안등·안심벨 밀집 구간 선호)

---

## 2. 안전점수 산식 (edge 단위)

기준점: edge 중점 `ST_LineInterpolatePoint(the_geom, 0.5)`

```
score = CLAMP(0~100, ROUND(
  70
  - (crime_max_grade / 10.0) * 35        /* 범죄 등급 패널티: 최대 -35 */
  + nearest_bonus(cctv,  150m) * 15      /* CCTV 근접 보너스 */
  + density_bonus(cctv,  cap=6) * 10     /* CCTV 밀도 보너스 */
  + nearest_bonus(light, 100m) * 10      /* 보안등 근접 */
  + density_bonus(light, cap=8) * 10     /* 보안등 밀도 */
  + nearest_bonus(bell,  100m) *  5      /* 안심벨 근접 (저가중치) */
  + density_bonus(bell,  cap=3) *  3     /* 안심벨 밀도 (저가중치) */
))

nearest_bonus(table, R) = CLAMP(0~1, (R - min_distance) / R) * maxBonus
density_bonus(table, cap) = CLAMP(0~1, count / cap) * maxBonus
```

점수 범위: 최저 35 (범죄 최고, 인프라 없음) ~ 최고 100

사용자 신고(report)는 미검증 데이터이므로 이 버전에서는 반영하지 않는다.

---

## 3. safety_cost 변환식

```
safety_cost = cost * (1.0 + (100 - safety_score) / 100.0 * 2.0)
```

K=2.0 기준 배율:

| safety_score | safety_cost 배율 |
|---|---|
| 100 (최안전) | × 1.0 |
| 70  (기본값) | × 1.6 |
| 35  (최위험) | × 2.3 |

- SAFE 모드: `safety_cost` 컬럼으로 pgr_dijkstra
- SHORTEST 모드: `cost` 컬럼(순수 거리)으로 pgr_dijkstra

---

## 4. 사전 계산 배치 스크립트

파일: `BE/db/compute-safety-cost.sql`

실행 순서:
1. `load-osm-walk-network.sh` 실행 (OSM 적재 완료)
2. `psql ... -f BE/db/compute-safety-cost.sql` 1회 수동 실행

스크립트 내용:
- `ways` 테이블에 `safety_score SMALLINT`, `safety_cost DOUBLE PRECISION` 컬럼 추가
- edge 중점 기준 lateral subquery로 인프라 집계 후 UPDATE
- `safety_cost IS NULL` edge는 pgr_dijkstra 쿼리에서 `cost` fallback

---

## 5. API 명세

### Endpoint

```
GET /api/route
```

### Query Parameters

| 파라미터 | 타입 | 필수 | 제약 |
|---|---|---|---|
| startLat | double | O | -90 ~ 90 |
| startLng | double | O | -180 ~ 180 |
| endLat | double | O | -90 ~ 90 |
| endLng | double | O | -180 ~ 180 |
| mode | string | O | `SAFE` 또는 `SHORTEST` |

### 정상 응답 (200 OK)

```json
{
  "coordinates": [
    {"lat": 37.5665, "lng": 126.9780},
    {"lat": 37.5650, "lng": 126.9760}
  ],
  "totalDistanceMeters": 1234.5,
  "estimatedSafetyScore": 78
}
```

- `coordinates`: 경로 노드 순서대로 정렬된 좌표 배열 (FE 카카오맵 폴리라인용)
- `totalDistanceMeters`: 경로 edge `cost` 합산 (SAFE 모드도 실제 거리 기준)
- `estimatedSafetyScore`: 경로 edge `safety_score` 평균값

### 에러 응답

| 상황 | HTTP | 메시지 |
|---|---|---|
| lat 범위 초과 | 400 | "위도는 -90~90 사이여야 합니다" |
| lng 범위 초과 | 400 | "경도는 -180~180 사이여야 합니다" |
| mode 값 오류 | 400 | "mode는 SAFE 또는 SHORTEST여야 합니다" |
| 최근접 노드 없음 | 404 | "경로 탐색 가능 구역이 아닙니다" |
| pgr_dijkstra 결과 없음 | 404 | "두 지점 사이 경로를 찾을 수 없습니다" |

---

## 6. 컴포넌트 구조

### 신규 파일

```
BE/db/compute-safety-cost.sql
BE/src/main/java/com/safewalk/route/
  RouteController.java
  RouteService.java
  dto/
    RouteResponse.java      record(List<Coordinate>, double totalDistanceMeters, int estimatedSafetyScore)
    Coordinate.java         record(double lat, double lng)
BE/src/test/java/com/safewalk/route/
  RouteControllerTest.java
  RouteServiceIntegrationTest.java
```

### 기존 파일 변경 없음

SafetyScoreCalculator, SafetyQueryService 등 기존 코드는 수정하지 않는다.

---

## 7. RouteService 처리 흐름

1. startLat/Lng → `ways_vertices_pgr` KNN(`<->` 연산자)으로 source 노드 id 조회
2. endLat/Lng → target 노드 id 조회 (노드 없으면 404)
3. mode에 따라 cost 컬럼 선택
4. `pgr_dijkstra` 실행 (결과 없으면 404)
5. 결과 node 목록 → `ways_vertices_pgr` join → 좌표 배열 조립
6. 경로 edge `cost` 합산 → `totalDistanceMeters`
7. 경로 edge `safety_score` 평균 → `estimatedSafetyScore`

최근접 노드 판단 기준: KNN 결과 노드와 요청 좌표 간 거리가 **1km 초과**이면 "경로 탐색 가능 구역이 아닙니다" 404 반환. (서울 도보망 밖 좌표 필터 목적)

에러 처리: `ResponseStatusException` inline (글로벌 핸들러 추가 없음)

---

## 8. 테스트 계획

### RouteControllerTest (@WebMvcTest, 6개)

1. lat 범위 초과 → 400
2. lng 범위 초과 → 400
3. 잘못된 mode 값 → 400
4. 노드 못 찾음 (서비스 404 throw) → 404
5. 경로 없음 (서비스 404 throw) → 404
6. 정상 SAFE 요청 → 200 + 필드 존재 확인

### RouteServiceIntegrationTest (@SpringBootTest, 3개)

1. SAFE 경로의 `estimatedSafetyScore` ≥ SHORTEST 경로의 `estimatedSafetyScore` (동일 두 좌표)
2. SHORTEST 경로의 `totalDistanceMeters` ≤ SAFE 경로의 `totalDistanceMeters`
3. 서울 외 좌표 (제주) → 404

---

## 9. 범위 밖

- 실시간 안전 가중치 계산 (배치 사전계산으로 대체)
- 사용자 신고 반영 (미검증 데이터, 추후 APPROVED 신고 penalty 추가 가능)
- night_time_penalty (야간 가중치, 추후 확장 가능)
- pgr_aStar / bidirectional 최적화 (419k edges 규모에서 dijkstra 실측 후 필요 시 전환)
- FE 연동 스펙 문서화 (별도 README 추가는 FE 팀과 협의 후)
