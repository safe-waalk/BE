# Report API 설계 문서

- **작성일**: 2026-07-19
- **브랜치**: TW
- **관련 문서**: `db/schema.sql`, `docs/report-api-onboarding.md`

## 범위

현재 `db/schema.sql`의 `report` 테이블을 스키마 변경 없이 그대로 사용한다. AI 분류 연동과 관리자 검토/승인은 이번 범위 밖이다.

## 엔드포인트

### POST /api/reports — 신고 등록

**Request body (JSON):**

```json
{
  "content": "골목 가로등이 없어요",
  "lat": 37.5665,
  "lng": 126.9780,
  "category": "LIGHTING",
  "severity": "HIGH"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| content | String | Y | 사용자 작성 원문 |
| lat | double | Y | 위도 (-90 ~ 90) |
| lng | double | Y | 경도 (-180 ~ 180) |
| category | String | N | 신고 카테고리 (허용값 목록 참고) |
| severity | String | N | 위험도 — HIGH / MEDIUM / LOW |

**허용 category 값:**
`LIGHTING`, `CCTV`, `SUSPICIOUS_AREA`, `ROAD_ENVIRONMENT`, `CRIME_RISK`, `NOISE_GROUP`, `WOMEN_SAFETY`, `FALSE_REPORT`, `PRIVACY_RISK`, `ETC`

**검증 규칙:**
- `content` — blank이면 400
- `lat` — -90 ~ 90 범위 벗어나면 400
- `lng` — -180 ~ 180 범위 벗어나면 400
- `category` — 제공 시 허용값 목록 외 값이면 400
- `severity` — 제공 시 `HIGH`/`MEDIUM`/`LOW` 외 값이면 400

**응답: `201 Created`**

```json
{
  "id": 1,
  "content": "골목 가로등이 없어요",
  "category": "LIGHTING",
  "severity": "HIGH",
  "status": "PENDING",
  "lat": 37.5665,
  "lng": 126.9780,
  "createdAt": "2026-07-19T12:00:00"
}
```

`status`는 항상 `PENDING`으로 저장된다. `geom`은 `ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)`으로 저장하고, INSERT 후 `RETURNING`으로 생성된 행을 읽어 응답한다.

---

### GET /api/reports — 신고 목록 조회

**응답: `200 OK`**

```json
[
  {
    "id": 1,
    "content": "골목 가로등이 없어요",
    "category": "LIGHTING",
    "severity": "HIGH",
    "status": "PENDING",
    "lat": 37.5665,
    "lng": 126.9780,
    "createdAt": "2026-07-19T12:00:00"
  }
]
```

필터 없이 `created_at DESC` 전체 반환. `geom` 역변환은 `ST_Y(geom) AS lat, ST_X(geom) AS lng`으로 처리한다.

## 컴포넌트 구조

```
com.safewalk.report
├── ReportController        ← HTTP 처리, 입력 검증
├── ReportService           ← DB 저장/조회 (NamedParameterJdbcTemplate)
└── dto/
    ├── ReportRequest       ← record: content, lat, lng, category, severity
    └── ReportResponse      ← record: id, content, category, severity, status, lat, lng, createdAt
```

- ORM 없음 — `NamedParameterJdbcTemplate` 직접 사용 (`SafetyQueryService` 동일 패턴)
- 전역 예외 핸들러 없음 — `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` 인라인 사용
- DTO는 Java 21 record

## 테스트

**`ReportControllerTest` (`@WebMvcTest`)**
- content blank → 400
- lat 범위 초과 → 400
- lng 범위 초과 → 400
- 허용되지 않은 category → 400
- 허용되지 않은 severity → 400
- 정상 요청 → 201 + 응답 필드 검증
- GET 정상 요청 → 200 + 배열 반환 검증

**`ReportServiceIntegrationTest` (`@SpringBootTest`)**
- 신고 저장 후 목록 조회 시 저장한 항목 포함 확인
- category/severity null로 저장 가능 확인
- geom 좌표 역변환 정확도 확인 (저장한 lat/lng와 응답 lat/lng 일치)

## 미결 사항 (이번 범위 외)

- AI 분류 연동 (`category`, `severity` 자동 파싱)
- 관리자 검토/승인 (`status` APPROVED/REJECTED 변경)
- 페이지네이션
- `status` 필터
- 스키마 확장 (`user_id`, `image_url`, `admin_weight`, `reviewed_at` 등)
