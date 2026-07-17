# publicOffice 제거 설계

**배경:** `GET /api/safety/summary`가 반환하던 `publicOffice`(관공서) 데이터는 실사용 가치가 낮아 더 이상 제공하지 않기로 했다. ([[2026-07-17-spatial-safety-query-api-design]] 에서 정의된 5종 중 하나를 제거)

**범위:**
- API 응답에서 `publicOffice` 필드 완전 제거 (breaking change, 별도 버저닝 없음 — 프로젝트 초기 단계이므로 즉시 반영)
- `db/schema.sql`의 `public_office` 테이블/인덱스 정의 삭제 (문서 성격의 스키마 파일만 수정)
- **실제 Supabase DB의 `public_office` 테이블은 그대로 둔다** — 운영 DB에 대한 DROP은 이번 작업 범위 밖

**변경 대상:**
1. `src/main/java/com/safewalk/safety/dto/SafetySummaryResponse.java` — `publicOffice` 필드 제거
2. `src/main/java/com/safewalk/safety/SafetyQueryService.java` — `PUBLIC_OFFICE_RADIUS_M` 상수와 관련 쿼리 호출 제거
3. `src/test/java/com/safewalk/safety/dto/SafetySummaryResponseSerializationTest.java` — publicOffice 관련 값/검증 제거
4. `src/test/java/com/safewalk/safety/SafetyQueryServiceIntegrationTest.java` — publicOffice 관련 검증 제거
5. `src/test/java/com/safewalk/safety/SafetyControllerTest.java` — publicOffice 관련 mock 값/검증 제거
6. `src/main/java/com/safewalk/HealthController.java` — 카운트 대상 테이블 목록에서 `public_office` 제외
7. `db/schema.sql` — `public_office` 테이블/인덱스 정의 삭제
8. `docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md`, `docs/superpowers/plans/2026-07-17-spatial-safety-query-api.md` — publicOffice 관련 서술 제거/갱신

**검증:** 기존 단위/통합 테스트 통과 + 수동으로 `/api/safety/summary`, `/api/health/db/counts` 응답에 publicOffice/public_office 키가 없는지 확인.
