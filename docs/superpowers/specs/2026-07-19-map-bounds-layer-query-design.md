# 지도 bounds 기반 레이어 조회 API 설계

- **작성일**: 2026-07-19
- **관련 문서**: `docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md`, `db/schema.sql`
- **상태**: 승인됨

## 배경 / 목적

기존 `GET /api/safety/summary`는 좌표 1개를 기준으로 반경 내 집계(개수, 최근접거리)만 반환한다. 지도 화면에 CCTV/보안등/안심벨/범죄구역을 마커로 찍으려면 화면에 보이는 사각형 영역(bounds) 안에 있는 개별 좌표 목록이 필요한데, summary API로는 얻을 수 없다.

이 스펙은 지도 SDK의 bounds(남서/북동 좌표)를 입력받아 그 영역 안의 인프라/범죄구역 포인트를 레이어별로 반환하는 API를 정의한다. 이 API는 향후 경로(route) 표시 화면에서도 재사용된다 — 경로를 그린 뒤 그 경로가 지나가는 영역의 bounds를 계산해 이 API로 마커를 조회하는 방식. 경로 자체의 세그먼트별 안전도 스코어링은 이 스펙의 범위가 아니다 (`2026-07-17-spatial-safety-query-api-design.md`에 이미 "OSRM 연동 이후 별도 스펙"으로 명시됨).

## 범위

**포함**:
- bounds(남서/북동 좌표) 기준 레이어별 개별 좌표 조회 API
- 대상 테이블: `cctv`, `security_light`, `safety_bell`, `crime_zone` (기존 summary와 동일 4종)
- 요청 시 레이어 선택 (`layers` 파라미터로 필요한 레이어만 조회)
- 응답 개수 상한 + bounds 크기 상한으로 과도한 응답/쿼리 비용 방지

**제외 (YAGNI)**:
- 경로(route) 세그먼트 기반 안전도 분석/스코어링 — OSRM 연동 이후 별도 스펙
- 클러스터링/그리드 집계(히트맵 등) — 필요해지면 별도 파라미터로 추가
- `report`(사용자 신고) 테이블 — 이번 레이어 목록에 포함하지 않음
- 정렬(ordering) — 요구사항 없음, DB 반환 순서 그대로 사용

## 아키텍처

기존 summary API(`SafetyController`/`SafetyQueryService`)와 완전히 분리된 클래스를 신설한다. 응답 형태(집계 vs 포인트 리스트)와 쿼리 방식(반경 vs bounding box)이 근본적으로 달라, 기존 서비스에 얹으면 책임이 섞이고 파일이 비대해진다.

```
com.safewalk.safety/
├─ SafetyController.java        (기존, 무변경)
├─ SafetyQueryService.java      (기존, 무변경)
├─ SafetyLayerController.java   (신규 — 엔드포인트)
├─ SafetyLayerQueryService.java (신규 — bounds 쿼리 + DTO 조립)
└─ dto/
   ├─ (기존 summary DTO들, 무변경)
   ├─ CctvPoint.java          (id, lat, lng, address, cameraCount)
   ├─ SecurityLightPoint.java (id, lat, lng, address)
   ├─ SafetyBellPoint.java    (id, lat, lng, address)
   ├─ CrimeZonePoint.java     (id, lat, lng, grade)
   └─ SafetyLayerResponse.java
```

별도 Repository 계층은 두지 않는다 (기존 패턴 그대로, `NamedParameterJdbcTemplate` 직접 사용, JPA/Hibernate Spatial 도입 안 함).

## 엔드포인트 계약

```
GET /api/safety/layers?swLat={}&swLng={}&neLat={}&neLng={}&layers={csv}
```

**파라미터**:
- `swLat` (required, double, -90~90) — bounds 남서 좌표 위도
- `swLng` (required, double, -180~180) — bounds 남서 좌표 경도
- `neLat` (required, double, -90~90) — bounds 북동 좌표 위도
- `neLng` (required, double, -180~180) — bounds 북동 좌표 경도
- `layers` (required, comma-separated string) — `cctv`, `securityLight`, `safetyBell`, `crimeZone` 중 1개 이상. 생략/빈 값/정의되지 않은 값이 섞여 있으면 400.

파라미터 이름은 카카오맵/네이버지도/구글맵 등 지도 SDK의 `LatLngBounds`가 공통으로 제공하는 southwest/northeast 코너 명칭을 그대로 따른다 — 프론트에서 SDK bounds 객체를 변환 없이 바로 전달할 수 있도록.

**응답 (200)**: 요청한 레이어 키만 포함하고, 각 값은 해당 레이어의 포인트 배열이다. 요청하지 않은 레이어는 키 자체가 응답에 없다.

```json
{
  "cctv": [
    {"id": 1, "lat": 37.5665, "lng": 126.9780, "address": "서울 중구 ...", "cameraCount": 2}
  ],
  "crimeZone": [
    {"id": 3, "lat": 37.5550, "lng": 126.9700, "grade": 7}
  ]
}
```

**레이어별 필드**:

| 레이어 | 필드 |
|---|---|
| cctv | id, lat, lng, address, cameraCount |
| securityLight | id, lat, lng, address |
| safetyBell | id, lat, lng, address |
| crimeZone | id, lat, lng, grade |

**레이어당 응답 개수 상한**: 500건 (SQL `LIMIT 500`). 초과분은 잘리며 별도 안내 필드 없음 — 프론트에서 줌인을 유도하는 방식으로 대응한다.

## 데이터 흐름

1. `SafetyLayerController`가 bounds 4개 값과 `layers` 파라미터를 파싱하고 검증한다 (범위, 박스 유효성, bounds 크기, layers 값).
2. `SafetyLayerQueryService`는 `layers`에 포함된 테이블에 대해서만 쿼리를 실행한다 (선택되지 않은 테이블은 쿼리 자체를 하지 않음).
3. 레이어별 쿼리 (인프라 3종 공통 패턴, `geom`이 Point이므로 bbox 연산자 `&&`로 GIST 인덱스를 그대로 활용):

```sql
SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, address, camera_count
FROM cctv
WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)
LIMIT 500
```

`security_light`/`safety_bell`은 `camera_count` 없이 동일 패턴. `crime_zone`은 `address`/`camera_count` 대신 `grade`를 조회한다.

4. 요청된 레이어의 결과만 `SafetyLayerResponse`의 해당 필드에 채우고, 요청하지 않은 필드는 `null`로 둔다. `SafetyLayerResponse`에 `@JsonInclude(JsonInclude.Include.NON_NULL)`을 적용해 `null` 필드가 JSON 직렬화에서 아예 빠지도록 한다 — 별도의 동적 Map 구조 없이 레코드 스타일을 유지하면서 "요청한 키만 응답"을 만족시킨다.

## 검증 / 에러 처리

기존 `SafetyController`와 동일하게 `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)`을 직접 던진다. 전역 예외 핸들러는 신설하지 않는다.

- `swLat`/`neLat`이 -90~90 밖이거나 `swLng`/`neLng`이 -180~180 밖이면 400
- `swLat >= neLat` 또는 `swLng >= neLng`이면 400 (잘못된 박스 — 날짜변경선을 넘는 bounds는 지원하지 않음, 국내 서비스 범위에서 불필요)
- **bounds 크기 상한**: 위경도 차이를 미터로 환산해 가로/세로 어느 한 변이라도 5,000m를 초과하면 400. 위도 1도 ≈ 111,320m로 계산하고, 경도는 평균 위도의 코사인 보정을 적용한다. DB 왕복 없이 서비스 레이어에서 계산하는 근사치이며, 정밀한 지리 계산이 아니라 과도한 쿼리 비용을 막기 위한 가드임을 명확히 한다.
- `layers`가 누락되었거나 빈 값이거나, 정의된 4개 값 외의 문자열이 포함되면 400

DB 쿼리 실패 시 별도 처리 없이 Spring 기본 500 응답에 위임한다 (기존 summary API와 동일한 방침).

## 테스트 전략

- `@WebMvcTest` 수준: bounds 파라미터 누락/범위 밖/박스 역전/5km 초과, `layers` 파라미터 누락/오타 → 400 검증 (DB 없이 컨트롤러 검증 로직만)
- 실데이터 통합 테스트: 알려진 실좌표를 포함하는 bounds로 조회했을 때 최소 1건 이상 반환되는지, 매우 넓은 bounds(제한 크기 이하 조건 안에서 데이터가 많은 지역)에서 `LIMIT 500`이 적용되는지 확인
- 수동 curl로 실제 앱 기동 후 확인 (기존 관례 그대로 — 여러 레이어 조합, 단일 레이어, bounds 초과 케이스)

## 미해결 / 향후 과제 (이번 범위 아님)

- 경로 세그먼트 기반 안전도 분석/스코어링 (OSRM 연동 이후 별도 스펙)
- 클러스터링/그리드 집계 (줌아웃 상태에서 히트맵 등이 필요해지면 별도 스펙)
