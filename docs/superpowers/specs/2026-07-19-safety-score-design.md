# 안전점수(위험진단) 알고리즘 설계

- **작성일**: 2026-07-19
- **관련 문서**: `safewalk_finetuning_project_plan.md` (Desktop, 10장 "안전점수 산식 설계"), `docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md`
- **상태**: 승인됨

## 배경 / 목적

원 기획서 10장은 OSRM 경로를 30~50m 세그먼트로 쪼갠 뒤 세그먼트별로 안전점수를 계산하는 것을 최종 목표로 한다. 하지만 이 코드베이스에는 아직 경로 탐색(OSRM) 연동이 전혀 없다.

이 스펙은 원 기획서의 산식을 **좌표 1개 기준**으로 먼저 구현한다. `crime_penalty`/`cctv_bonus`/`light_bonus`만 반영하고, 기존 `SafetyQueryService.getSummary()`가 반환하는 데이터를 그대로 재사용한다. 이렇게 만들어두면 나중에 경로 탐색 API가 추가됐을 때 세그먼트 중심좌표마다 이 API를 반복 호출하는 식으로 재사용 가능한 기반 블록이 된다.

## 범위

**포함**:
- `GET /api/safety/score?lat={}&lng={}` — 좌표 기준 위험점수(0~100)와 세부 내역(`crimePenalty`, `cctvBonus`, `lightBonus`) 반환

**제외 (YAGNI)**:
- `report_penalty` — 신고 API와 관리자 채택(승인) 플로우가 아직 없어서 계산할 데이터 자체가 없음. 신고 기능이 갖춰진 뒤 별도 스펙에서 추가
- `night_time_penalty` — 위치와 무관한 flat 감점이라 좌표 간 비교에 의미가 없어서 제외 (예: 모든 좌표에 동일하게 -5/-10이 적용되면 상대적 순위는 그대로임). 나중에 경로 비교/임계값 판정처럼 절대 점수가 의미를 갖는 용도가 생기면 재검토
- 경찰서/버스정류장/택시승강장 접근성 가점 — 관련 데이터가 DB에 없음
- 경로 세그먼트 단위 계산 — OSRM 연동이 필요한 별도 스펙

## 아키텍처

```
com.safewalk.safety/
├─ SafetyScoreCalculator.java   (신규 — 순수 계산, DB 접근 없음, Spring 빈 아님)
├─ SafetyScoreController.java   (신규 — 엔드포인트, SafetyQueryService.getSummary() 호출 후 Calculator에 위임)
└─ dto/
   └─ SafetyScoreResponse.java (신규)
```

`SafetyScoreCalculator`는 `SafetySummaryResponse`를 입력으로 받아 `SafetyScoreResponse`를 반환하는 순수 함수형 클래스다. 외부 의존성이 없으므로 Spring 빈으로 등록하지 않고 컨트롤러가 `new`로 직접 생성한다. 산식(clamp, null 처리 등)을 DB 접근 없이 빠른 단위 테스트로 검증할 수 있게 하기 위한 의도적인 분리다.

기존 `SafetyQueryService`/`SafetyController`/`SafetyLayerController`/`SafetyLayerQueryService`는 전혀 건드리지 않는다.

## 산식

```
crimeRiskNormalized = (crimeZone.maxGrade ?? 0) / 10.0
crimePenalty = crimeRiskNormalized * 35

nearestCctvBonus = clamp((150 - cctv.nearestDistance) / 150, 0, 1) * 15   // cctv.nearestDistance가 null(반경 내 없음)이면 0
cctvDensityBonus = clamp(cctv.count / 6.0, 0, 1) * 10
cctvBonus = nearestCctvBonus + cctvDensityBonus

nearestLightBonus = clamp((100 - securityLight.nearestDistance) / 100, 0, 1) * 10   // null이면 0
lightDensityBonus = clamp(securityLight.count / 8.0, 0, 1) * 10
lightBonus = nearestLightBonus + lightDensityBonus

rawScore = 70 - crimePenalty + cctvBonus + lightBonus
score = round(clamp(rawScore, 0, 100))
```

기존 `SafetyQueryService`의 반경(cctv=150m, securityLight=100m, crimeZone=150m)을 그대로 사용하므로, 원 기획서의 200m/80m 수치 대신 이 반경으로 정규화했다 — 새 쿼리를 추가하지 않고 기존 서비스를 그대로 재사용하기 위한 결정. `safetyBell`(안심벨) 데이터는 원 기획서 산식에도 포함되어 있지 않아 이번에도 반영하지 않는다.

`crimeZone.maxGrade`가 `null`(반경 내 범죄구역 없음)이면 `crimeRiskNormalized = 0`으로 처리한다. `cctv.nearestDistance`/`securityLight.nearestDistance`가 `null`(반경 내 데이터 없음)이면 해당 nearest 보너스는 0으로 처리한다 (density 보너스는 `count`가 이미 0이므로 자연히 0이 됨).

## 엔드포인트 계약

```
GET /api/safety/score?lat={}&lng={}
```

**파라미터**: `lat`(required, double, -90~90), `lng`(required, double, -180~180)

**응답 (200)**:
```json
{"score": 62, "crimePenalty": 17.5, "cctvBonus": 20.0, "lightBonus": 10.0}
```

`score`는 반올림한 정수(0~100), `crimePenalty`/`cctvBonus`/`lightBonus`는 반올림하지 않은 소수(디버깅/투명성 목적).

## 데이터 흐름

1. `SafetyScoreController`가 `lat`/`lng`를 검증한다 (기존 `SafetyController`와 동일하게 -90~90/-180~180 범위, 누락/범위 밖이면 400).
2. `SafetyQueryService.getSummary(lat, lng)`를 호출해 `SafetySummaryResponse`를 얻는다 (기존 메서드, 무변경).
3. `new SafetyScoreCalculator().calculate(summaryResponse)`로 위 산식을 적용해 `SafetyScoreResponse`를 만든다.
4. `SafetyScoreResponse`를 200으로 반환한다.

## 에러 처리

기존 `SafetyController`와 동일하게 `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)`을 컨트롤러에서 직접 던진다. 전역 예외 핸들러는 신설하지 않는다. DB 쿼리 실패 시 별도 처리 없이 Spring 기본 500 응답에 위임한다 (기존 방침과 동일).

## 테스트 전략

- `SafetyScoreCalculator`는 순수 함수이므로 DB 없는 단위 테스트로 엣지 케이스를 검증한다: 범죄구역 없음(감점 0), 최대 등급(grade=10 → crimePenalty=35), CCTV/보안등 반경 내 없음(해당 보너스 0), 모든 보너스가 최대치일 때 100을 넘지 않고 clamp되는지, 범죄가 최대이고 인프라도 전혀 없을 때 0 밑으로 내려가지 않는지
- `SafetyScoreController`는 `@WebMvcTest` + `SafetyQueryService` mock으로 `lat`/`lng` 검증(400 케이스들) + 정상 흐름(200, mock summary 기준으로 계산된 값이 그대로 나오는지) 검증
- 수동 curl로 실제 앱 기동 후 확인 (실제 좌표로 점수가 합리적인 범위에서 나오는지)

## 미해결 / 향후 과제 (이번 범위 아님)

- `report_penalty` (신고 API + 관리자 채택 플로우 이후)
- `night_time_penalty` (절대 점수 비교/임계값 판정 같은 실사용처가 생기면 재검토)
- 경찰서/버스정류장/택시승강장 접근성 가점 (관련 데이터 적재 이후)
- 경로 세그먼트 단위 계산 (OSRM 연동 이후 별도 스펙 — 이 API를 세그먼트마다 반복 호출하는 방식으로 재사용 예정)
