# 신고(Report) API 온보딩 문서

- **작성일**: 2026-07-19
- **대상**: 신고(report) API를 새로 맡는 협업자
- **관련 문서**: `db/schema.sql`, `docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md`, `docs/superpowers/specs/2026-07-19-map-bounds-layer-query-design.md`, Desktop의 `safewalk_finetuning_project_plan.md` (로컬 전용 파일이라 이 저장소에는 없음 — 신고 관련 내용만 아래에 발췌해둠)

## 왜 독립적으로 작업 가능한가

현재 `SY` 브랜치에서 지도 bounds 레이어 조회 API(`cctv`/`security_light`/`safety_bell`/`crime_zone`, `com.safewalk.safety` 패키지)를 작업 중이다. `report` 테이블/API는 완전히 다른 패키지·다른 테이블이라 병행 작업해도 파일 충돌이 없다. 새 브랜치를 따로 파서 진행하면 된다 (예: `report-api`).

## 지금 실제로 존재하는 것 (기준: `db/schema.sql`)

```sql
CREATE TABLE report (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,            -- 사용자가 쓴 원문
    category VARCHAR(30),             -- LLM이 파싱한 카테고리 (LIGHTING 등)
    severity VARCHAR(10),             -- LLM이 파싱한 위험도 (HIGH/MID/LOW)
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED
    geom GEOMETRY(Point, 4326),
    created_at TIMESTAMP DEFAULT now()
);
```

API/컨트롤러/서비스 코드는 아직 없다. `HealthController`의 테이블 카운트 목록에만 포함되어 있다 (`GET /api/health/db/counts`).

## 주의: 원 기획 문서와 실제 스키마가 다르다

Desktop의 `safewalk_finetuning_project_plan.md` (12.5 reports)에는 `user_id`, `image_url`, `ai_category`, `ai_severity`, `ai_summary`, `admin_weight`, `reviewed_at` 등이 포함된 훨씬 풍부한 스키마가 정의되어 있다. 그건 최종 비전이고, **현재 `db/schema.sql`에는 반영되어 있지 않다**.

이번 작업 범위를 잡을 때 "지금 스키마 그대로 최소 기능만 구현" vs "스키마 마이그레이션부터 포함"을 브레인스토밍 단계에서 먼저 정해야 한다. 스키마 변경은 범위가 커지므로 신중히 판단할 것 — 이 저장소는 지금까지 기능을 작게 쪼개서 스펙/계획을 나눠온 관례가 있다 (`docs/superpowers/specs/` 참고).

## 참고: 기획서에 있던 신고 카테고리 (11.3)

| 카테고리 | 설명 |
|---|---|
| LIGHTING | 가로등 부족, 어두운 골목 |
| CCTV | CCTV 부족, 사각지대 |
| SUSPICIOUS_AREA | 수상한 사람, 불안감 |
| ROAD_ENVIRONMENT | 좁은 길, 막다른 길, 공사장 |
| CRIME_RISK | 범죄주의구간 관련 |
| NOISE_GROUP | 취객, 무리, 소란 |
| WOMEN_SAFETY | 여성 안심 귀가 관련 |
| FALSE_REPORT | 허위/장난 신고 |
| PRIVACY_RISK | 개인정보 노출 가능 신고 |
| ETC | 기타 |

`severity`는 실제 스키마상 `VARCHAR(10)`이라 `HIGH`/`MID`/`LOW` 같은 짧은 값을 예상한 설계다. 기획서의 AI 분류 예시 응답에는 `MEDIUM`(6자)이 쓰였는데 이러면 컬럼 길이를 초과하니, 값 목록을 확정할 때 이 불일치를 먼저 정리해야 한다.

## 참고: 기획서 초안의 신고 흐름

1. 사용자가 신고 작성 (위치, 카테고리, 설명, 사진)
2. 서버가 신고 저장 (`status=PENDING`)
3. (향후) AI가 신고 문장 분류 / 위험도 판단 보조
4. 관리자가 검토 후 채택된 신고만 안전점수에 반영

이번 스펙 범위에서 3번(AI 연동)과 4번(관리자 검토/승인)까지 포함할지는 브레인스토밍에서 결정한다. **최소 범위 추천**: 신고 등록(`POST /api/reports`) + 목록 조회 정도로 시작하고, AI 분류/관리자 검토는 별도 스펙으로 미루는 것. 실제 스키마에도 아직 관리자 검토용 컬럼(`admin_weight`, `reviewed_at` 등)이 없어서, 이 부분까지 하려면 스키마 변경이 선행돼야 한다.

## 진행 방식 (이 저장소 컨벤션)

1. 새 브랜치 생성: `git checkout -b report-api` (또는 본인 이니셜)
2. `superpowers:brainstorming` 스킬로 스펙 확정 → `docs/superpowers/specs/YYYY-MM-DD-*.md`
3. `superpowers:writing-plans`로 구현 계획 작성 → `docs/superpowers/plans/YYYY-MM-DD-*.md`
4. TDD로 Task 단위 구현, Task 하나 끝날 때마다 커밋
5. 완료되면 push 후 PR

## 코드 컨벤션 (기존 안전 API 참고)

- 패키지: `com.safewalk.report` (안전 API가 `com.safewalk.safety`인 것과 대응), DTO는 `com.safewalk.report.dto`
- ORM 안 씀 — `NamedParameterJdbcTemplate` 직접 사용 (`SafetyQueryService` 참고)
- 검증 실패는 `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` 직접 던짐, 전역 예외 핸들러 없음
- Java 21 record로 DTO 작성
- `@WebMvcTest` + 실DB 통합 테스트 조합 (`SafetyControllerTest`/`SafetyQueryServiceIntegrationTest` 참고)

## 커밋 규칙

`CLAUDE.md` 참고 — 커밋 메시지에 `Co-Authored-By`/`Claude-Session` 트레일러를 붙이지 않는다.
