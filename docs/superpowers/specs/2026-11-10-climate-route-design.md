# 기후(폭염/호우) 경로 설계 문서

**날짜:** 2026-11-10
**범위:** BE + 로컬 데이터 — 폭염/호우 경보를 보행 경로 안내로 바꾸기

---

## 1. 목표

경보 문구를 "외출을 자제하세요"에서 **"이쪽으로 가세요"**로 바꾼다.

- **HEAT**: 그늘이 많고 무더위쉼터 근처를 지나는 경로
- **RAIN**: 침수 이력 구간과 지하보도·지하차도를 피하는 경로, 침수 구역 안에서 출발하면 가까운 고지대 대피 지점
- 경보 자체는 기상청 특보 API 키가 없어 **시나리오 시뮬레이션**(`simulated: true`)으로 낸다

시연 구간: 한성대입구역(37.5884, 127.0062) → 한성대학교(37.5826, 127.0103).
적재 범위: S=37.572, W=126.993, N=37.600, E=127.024 (`db/climate/bbox.env`).

---

## 2. 데이터 (로컬 PostGIS + pgRouting, `db/climate/`)

기존 Supabase 그래프는 osm2pgrouting으로 만들어 `tunnel`·`covered` 태그가 없다.
기후 비용에 필요한 태그를 살리려고 **OSM XML을 직접 파싱해 그래프를 만든다**(`build_graph.py`, 표준 라이브러리만).

| 테이블 | 내용 | 행 수 |
|---|---|---|
| `ways` | 교차점에서 분할한 보행 간선, 형상점 전체 보존 | 5,703 |
| `ways_vertices_pgr` | 분할점 + SRTM 쌍선형 보간 고도 | 4,141 |
| `cooling_shelter` | 서울 무더위쉼터(OA-21065) 중 bbox 안 | 124 |
| `flood_trace` | 서울 침수흔적도(OA-15636) 2010~2025, bbox로 자름 | 156 |

기존 안전 레이어 테이블(`crime_zone`, `cctv`, `security_light`, `safety_bell`, `report`)은 빈 채로 만든다(기존 API 보호).

### 간선 속성

- `underpass`: `tunnel`이 `no`·`building_passage`가 아니거나 `layer < 0`이면 지하. 보행류 highway면 `PEDESTRIAN`, 차도류면 `ROAD`, 아니면 `NONE`
- `covered`: `covered`가 `no`가 아님 또는 `tunnel=building_passage`
- `shade_ratio`: EPSG:5186에서 `|간선 ∩ 그늘 면 합집합| / |간선|`
  - 건물: 바닥 + 버퍼 `min(높이 × 0.5, 15m)`, 높이는 `height` → `building:levels × 3.2` → 9m
  - 가로수 반경 3.5m, 숲·공원 면 전체
  - 지하보도·`covered`는 1.0
- `in_flood_zone`: 침수흔적 폴리곤에서 **10m 이내**(`ST_DWithin(geography, 10)`)
- `near_shelter`: 무더위쉼터 50m 이내

### 비용

```
heat_cost = length_m * (1 + 1.5 * (1 - shade_ratio)) * (near_shelter ? 0.85 : 1.0)
rain_cost = length_m * (in_flood_zone ? 8 : 1) * (underpass <> 'NONE' ? 30 : 1)
```

비용이 NULL이거나 0 이하인 간선이 있으면 적재 트랜잭션을 롤백한다.
`load.sh`는 몇 번 돌려도 같은 결과다(깨끗한 DB·실DB 각 2회 실행, 4개 테이블 md5 일치 확인).

### 결정: `in_flood_zone`을 "교차"가 아니라 "10m 이내"로

침수흔적은 침수된 **필지·건물 폴리곤**이라 도로 중심선과 거의 교차하지 않는다.
교차 기준이면 true 간선이 17개(964m)뿐이고, 시연 최단경로는 2011년 흔적에서 1.7m 떨어져 지나가는데도 false가 된다.
그러면 최단경로의 `rain_cost`가 길이와 같아 **어떤 계수로도 RAIN이 SHORTEST와 같아진다**.
10m로 바꾸면 221간선(14.7km)이 true가 된다. `FLOOD_NEAR_M=0`으로 교차 기준으로 되돌릴 수 있다.
그 결과 API의 `exposure.floodZoneMeters`도 "침수흔적 10m 이내 구간 길이"를 뜻한다.
`refuge` 판정은 버퍼 없이 폴리곤을 직접 쓴다.

---

## 3. API

상세 계약은 `README.md`의 API 절이 기준이다. 요약:

| Endpoint | 요점 |
|---|---|
| `GET /api/route` | `mode`에 `HEAT`/`RAIN` 추가. 기존 3필드 유지 + `exposure`(전 모드), `shelters`(HEAT만, 150m·20개), `refuge`(RAIN·조건부) |
| `GET /api/climate/shelters` | bounds 안 쉼터, `/api/safety/layers` 검증 관례, LIMIT 500 |
| `GET /api/climate/flood-zones` | `polygons[폴리곤][링][점]={lat,lng}`, bbox로 자르고 약 2m 단순화, LIMIT 300 |
| `GET /api/climate/alert` | `NONE`/`HEAT`/`RAIN` 고정 문구, `simulated: true`, DB 미사용 |

### `coordinates` 형상점

기존 구현은 `pgr_dijkstra`의 꼭짓점만 반환해 선이 블록을 가로질렀다. 이제:

1. 결과 각 행 `(node, edge)`를 `ways`와 조인해 `node = source`면 `the_geom`, 아니면 `ST_Reverse(the_geom)`
2. `ST_DumpPoints`로 점을 꺼내 경로 순서대로 정렬
3. Java(`RouteAssembler`)에서 직전 점과 같은 점(1e-7도)은 건너뛴다 — 이음새 중복 제거

전제(`the_geom` 첫 점 = `source`)는 적재 데이터에서 위반 0건으로 확인했다.

### `refuge`

RAIN에서 출발지(없으면 도착지)가 `flood_trace` 안일 때만. 반경 1000m 꼭짓점 중
(a) 어떤 `flood_trace`에도 안 들어 있고 (b) 고도 ≥ 주변(반경 100m 꼭짓점 평균) + 3m 이고
(c) 기준 지점이 스냅된 꼭짓점과 같은 연결 컴포넌트(`pgr_connectedComponents`)인 것 가운데 가장 가까운 것.
없으면 `null`.

---

## 4. 검증 결과 (2026-11-10, 로컬 DB + `bootRun` local 프로필, `compare-modes.sh`)

| 모드 | 거리 | 좌표 수 | 그늘 비율 | 침수 근접 m | 쉼터 |
|---|---|---|---|---|---|
| SHORTEST | 873.3m | 59 | 0.145 | 172.9 | 0 |
| SAFE | 873.3m | 59 | 0.145 | 172.9 | 0 |
| HEAT | 908.1m | 64 | 0.232 | 172.9 | 1 |
| RAIN | 883.5m | 58 | 0.137 | 0.0 | 0 |

- 세 모드(SHORTEST/HEAT/RAIN) 경로가 실제로 다르다. 계수는 시작값 그대로다
- SAFE = SHORTEST는 로컬 DB에 안전 데이터가 없어 `cost`로 폴백한 결과다
- 침수 구역 안 출발(37.578365, 127.0086377 → 한성대학교, RAIN): `refuge` 고도 92.23m, 35.3m 거리
- `./gradlew test`: 77개 중 10개 실패, 4 skipped. 실패 10개는 작업 전 베이스라인과 이름이 같다(Supabase 접속 불가 9 + 기존 404 단언 1)
- `SPRING_PROFILES_ACTIVE=local`에서 새 통합 테스트 22개 전부 통과

---

## 5. 한계 — 실데이터 / 근사 / 시뮬레이션

| 구분 | 항목 |
|---|---|
| 실데이터 | OSM 도로·건물·가로수·공원, SRTM 고도, 무더위쉼터, 침수흔적도 14개 연도 |
| 근사 | 그늘: 태양 고도·방위 미반영 고정 버퍼, 건물 4,140개 중 3,585개가 기본 높이 9m, 간선은 도로 중심선 |
| 근사 | 침수: 과거 흔적 전 연도 합집합, 강우·배수·깊이 미반영 |
| 근사 | 고도: SRTM 30m 격자 |
| 시뮬레이션 | 경보(`/api/climate/alert`) |

알려진 문제:

- 시연 최단경로 31간선 중 17간선(삼선교로4길 일대)은 주변 20m 안에 OSM 건물이 없어 그늘 0 — **데이터 공백**이지 실제 그늘이 없는 게 아니다
- (2026-11-16 해결) `tunnel=building_passage`(9간선)가 지하로 분류돼 RAIN에서 ×30을 받던 문제 — 지하 분류에서 빼고 `covered`로만 둔다.
  지하 간선이 39개에서 30개로 줄었고 시연 구간 세 경로는 변하지 않았다
- (2026-11-16 해결) `refuge`가 걸어서 갈 수 없는 꼭짓점일 수 있던 문제 — 같은 연결 컴포넌트 조건 추가, 통합 테스트로 대피 지점까지 경로 존재 확인
- 시연 구간 출발·도착이 침수흔적 밖이라 시연 경로로는 `refuge`가 나오지 않는다
- 기존 SAFE/SHORTEST 통합 테스트는 시청 좌표를 써서 로컬 적재 범위 밖(404)이다
- 침수 폴리곤 위치를 현장 자료와 대조하지 않았다(`.prj` 좌표계만 확인)
- FE에서 지도에 그려 보지 않았다
