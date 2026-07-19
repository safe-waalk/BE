# 안전점수(위험진단) 알고리즘 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 좌표 하나를 받아 기존 `SafetyQueryService.getSummary()` 데이터를 기반으로 안전점수(0~100)와 세부 내역을 계산하는 `GET /api/safety/score` 엔드포인트를 만든다.

**Architecture:** `com.safewalk.safety` 패키지에 순수 계산 클래스 `SafetyScoreCalculator`(Spring 빈 아님, DB 접근 없음)와 `SafetyScoreController`를 추가한다. 컨트롤러가 기존 `SafetyQueryService.getSummary()`를 호출한 뒤 결과를 Calculator에 넘겨 점수를 계산한다.

**Tech Stack:** Spring Boot 4.0.7, JUnit 5 + AssertJ + Mockito, Java 21 records.

## Global Constraints

- 패키지: `com.safewalk.safety` (컨트롤러/계산 클래스), `com.safewalk.safety.dto` (DTO)
- `SafetyScoreCalculator`는 Spring 빈으로 등록하지 않는다 — 의존성이 없는 순수 클래스이므로 컨트롤러에서 `new`로 직접 생성
- 새 DB 쿼리를 추가하지 않는다 — 기존 `SafetyQueryService.getSummary(double lat, double lng): SafetySummaryResponse`를 그대로 재사용
- 엔드포인트: `GET /api/safety/score?lat={}&lng={}`
- 응답: `{"score": <int 0~100>, "crimePenalty": <double>, "cctvBonus": <double>, "lightBonus": <double>}`
- 산식 상수: `BASE_SCORE=70`, `CRIME_MAX_PENALTY=35`, `CRIME_MAX_GRADE=10`(정규화 분모), CCTV `NEAREST_RADIUS_M=150`/`NEAREST_MAX_BONUS=15`/`DENSITY_CAP_COUNT=6`/`DENSITY_MAX_BONUS=10`, 보안등 `NEAREST_RADIUS_M=100`/`NEAREST_MAX_BONUS=10`/`DENSITY_CAP_COUNT=8`/`DENSITY_MAX_BONUS=10`
- `crimeZone.maxGrade`가 `null`이면 crimePenalty=0. `cctv`/`securityLight`의 `nearestDistance`가 `null`이면 해당 nearest 보너스는 0
- 검증: 전역 예외 핸들러 신설 안 함 — `ResponseStatusException` 직접 사용 (기존 `SafetyController` 패턴)
- 참고 스펙: `docs/superpowers/specs/2026-07-19-safety-score-design.md`

---

### Task 1: SafetyScoreCalculator (순수 계산) + SafetyScoreResponse DTO

**Files:**
- Create: `src/main/java/com/safewalk/safety/dto/SafetyScoreResponse.java`
- Create: `src/main/java/com/safewalk/safety/SafetyScoreCalculator.java`
- Test: `src/test/java/com/safewalk/safety/SafetyScoreCalculatorTest.java`

**Interfaces:**
- Consumes: `SafetySummaryResponse`, `InfraSummary`, `CrimeZoneSummary` (기존 `com.safewalk.safety.dto`, 무변경)
- Produces: `SafetyScoreResponse(int score, double crimePenalty, double cctvBonus, double lightBonus)`, `new SafetyScoreCalculator().calculate(SafetySummaryResponse summary): SafetyScoreResponse` — Task 2(컨트롤러)가 이 타입/메서드를 그대로 사용.

이 테스트는 DB 없이 순수 단위 테스트로 실행된다 (`SafetySummaryResponse`를 직접 생성해서 입력으로 준다).

- [x] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/SafetyScoreCalculatorTest.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.CrimeZoneSummary;
import com.safewalk.safety.dto.InfraSummary;
import com.safewalk.safety.dto.SafetyScoreResponse;
import com.safewalk.safety.dto.SafetySummaryResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyScoreCalculatorTest {

    private final SafetyScoreCalculator calculator = new SafetyScoreCalculator();

    @Test
    void noCrimeZoneNoInfraReturnsBaseScore() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.score()).isEqualTo(70);
        assertThat(result.crimePenalty()).isEqualTo(0.0);
        assertThat(result.cctvBonus()).isEqualTo(0.0);
        assertThat(result.lightBonus()).isEqualTo(0.0);
    }

    @Test
    void maxCrimeGradeWithNoInfraYieldsWorstRealisticScore() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(1, 50.0, 10)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.crimePenalty()).isEqualTo(35.0);
        assertThat(result.score()).isEqualTo(35);
    }

    @Test
    void maxBonusesClampScoreAt100() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(10, 0.0),
                new InfraSummary(10, 0.0),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.cctvBonus()).isEqualTo(25.0);
        assertThat(result.lightBonus()).isEqualTo(20.0);
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void cctvNearestBonusScalesWithDistanceWithinRadius() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(0, 75.0),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.cctvBonus()).isEqualTo(7.5);
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyScoreCalculatorTest"`
Expected: FAIL — `SafetyScoreCalculator`/`SafetyScoreResponse` 클래스가 없어서 컴파일 에러

- [x] **Step 3: DTO 구현**

`src/main/java/com/safewalk/safety/dto/SafetyScoreResponse.java`:

```java
package com.safewalk.safety.dto;

public record SafetyScoreResponse(int score, double crimePenalty, double cctvBonus, double lightBonus) {
}
```

- [x] **Step 4: Calculator 구현**

`src/main/java/com/safewalk/safety/SafetyScoreCalculator.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.SafetyScoreResponse;
import com.safewalk.safety.dto.SafetySummaryResponse;

public class SafetyScoreCalculator {

    private static final double BASE_SCORE = 70.0;
    private static final double CRIME_MAX_PENALTY = 35.0;
    private static final double CRIME_MAX_GRADE = 10.0;

    private static final double CCTV_NEAREST_RADIUS_M = 150.0;
    private static final double CCTV_NEAREST_MAX_BONUS = 15.0;
    private static final double CCTV_DENSITY_CAP_COUNT = 6.0;
    private static final double CCTV_DENSITY_MAX_BONUS = 10.0;

    private static final double LIGHT_NEAREST_RADIUS_M = 100.0;
    private static final double LIGHT_NEAREST_MAX_BONUS = 10.0;
    private static final double LIGHT_DENSITY_CAP_COUNT = 8.0;
    private static final double LIGHT_DENSITY_MAX_BONUS = 10.0;

    public SafetyScoreResponse calculate(SafetySummaryResponse summary) {
        double crimePenalty = calculateCrimePenalty(summary.crimeZone().maxGrade());
        double cctvBonus = calculateNearestBonus(summary.cctv().nearestDistance(), CCTV_NEAREST_RADIUS_M, CCTV_NEAREST_MAX_BONUS)
                + calculateDensityBonus(summary.cctv().count(), CCTV_DENSITY_CAP_COUNT, CCTV_DENSITY_MAX_BONUS);
        double lightBonus = calculateNearestBonus(summary.securityLight().nearestDistance(), LIGHT_NEAREST_RADIUS_M, LIGHT_NEAREST_MAX_BONUS)
                + calculateDensityBonus(summary.securityLight().count(), LIGHT_DENSITY_CAP_COUNT, LIGHT_DENSITY_MAX_BONUS);

        double rawScore = BASE_SCORE - crimePenalty + cctvBonus + lightBonus;
        int score = (int) Math.round(clamp(rawScore, 0.0, 100.0));

        return new SafetyScoreResponse(score, crimePenalty, cctvBonus, lightBonus);
    }

    private double calculateCrimePenalty(Integer maxGrade) {
        double normalized = maxGrade == null ? 0.0 : maxGrade / CRIME_MAX_GRADE;
        return normalized * CRIME_MAX_PENALTY;
    }

    private double calculateNearestBonus(Double nearestDistance, double radiusMeters, double maxBonus) {
        if (nearestDistance == null) {
            return 0.0;
        }
        return clamp((radiusMeters - nearestDistance) / radiusMeters, 0.0, 1.0) * maxBonus;
    }

    private double calculateDensityBonus(int count, double capCount, double maxBonus) {
        return clamp(count / capCount, 0.0, 1.0) * maxBonus;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
```

- [x] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyScoreCalculatorTest"`
Expected: PASS (4개 테스트 모두 통과, DB 연결 없음)

- [x] **Step 6: 커밋**

```bash
git add src/main/java/com/safewalk/safety/dto/SafetyScoreResponse.java src/main/java/com/safewalk/safety/SafetyScoreCalculator.java src/test/java/com/safewalk/safety/SafetyScoreCalculatorTest.java
git commit -m "feat: add SafetyScoreCalculator with point-based scoring formula"
```

---

### Task 2: SafetyScoreController (HTTP 엔드포인트)

**Files:**
- Create: `src/main/java/com/safewalk/safety/SafetyScoreController.java`
- Test: `src/test/java/com/safewalk/safety/SafetyScoreControllerTest.java`

**Interfaces:**
- Consumes: `SafetyQueryService.getSummary(double lat, double lng): SafetySummaryResponse` (기존, 무변경), `SafetyScoreCalculator.calculate(SafetySummaryResponse): SafetyScoreResponse` (Task 1)
- Produces: `GET /api/safety/score?lat={}&lng={}` HTTP 엔드포인트

- [x] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/SafetyScoreControllerTest.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.CrimeZoneSummary;
import com.safewalk.safety.dto.InfraSummary;
import com.safewalk.safety.dto.SafetySummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SafetyScoreController.class)
class SafetyScoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SafetyQueryService safetyQueryService;

    @Test
    void missingLatReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/score").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/score").param("lat", "91").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lngOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/score").param("lat", "37.5").param("lng", "181"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestReturnsComputedScore() throws Exception {
        SafetySummaryResponse mockSummary = new SafetySummaryResponse(
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );
        when(safetyQueryService.getSummary(anyDouble(), anyDouble())).thenReturn(mockSummary);

        mockMvc.perform(get("/api/safety/score").param("lat", "37.5665").param("lng", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(70))
                .andExpect(jsonPath("$.crimePenalty").value(0.0))
                .andExpect(jsonPath("$.cctvBonus").value(0.0))
                .andExpect(jsonPath("$.lightBonus").value(0.0));
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyScoreControllerTest"`
Expected: FAIL — `SafetyScoreController` 클래스가 없어서 컴파일 에러

- [x] **Step 3: 컨트롤러 구현**

`src/main/java/com/safewalk/safety/SafetyScoreController.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.SafetyScoreResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SafetyScoreController {

    private final SafetyQueryService safetyQueryService;
    private final SafetyScoreCalculator safetyScoreCalculator = new SafetyScoreCalculator();

    public SafetyScoreController(SafetyQueryService safetyQueryService) {
        this.safetyQueryService = safetyQueryService;
    }

    @GetMapping("/api/safety/score")
    public SafetyScoreResponse getScore(@RequestParam double lat, @RequestParam double lng) {
        if (lat < -90 || lat > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat must be between -90 and 90");
        }
        if (lng < -180 || lng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lng must be between -180 and 180");
        }
        return safetyScoreCalculator.calculate(safetyQueryService.getSummary(lat, lng));
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyScoreControllerTest"`
Expected: PASS

- [x] **Step 5: 커밋**

```bash
git add src/main/java/com/safewalk/safety/SafetyScoreController.java src/test/java/com/safewalk/safety/SafetyScoreControllerTest.java
git commit -m "feat: add GET /api/safety/score endpoint"
```

---

### Task 3: 수동 End-to-End 검증

**Files:** 없음 (코드 변경 없음, 실행 중인 앱에 대한 수동 확인)

**Interfaces:**
- Consumes: `GET /api/safety/score?lat={}&lng={}` (Task 2)

- [x] **Step 1: 애플리케이션 기동**

Run: `./gradlew bootRun` (백그라운드 실행)
Expected: 로그에 `Started SafewalkApplication` 출력
실측: `Started SafewalkApplication in 0.972 seconds` 로그 확인, Tomcat 8080 포트로 기동됨

- [x] **Step 2: 실제 CCTV/보안등이 있는 좌표로 점수 조회**

Run: `curl -s "http://localhost:8080/api/safety/score?lat=37.5665&lng=126.9780"`
Expected: HTTP 200, `{"score": <0~100 사이 정수>, "crimePenalty": <double>, "cctvBonus": <double>, "lightBonus": <double>}` 형태. `score`가 `70 - crimePenalty + cctvBonus + lightBonus`를 반올림/clamp한 값과 일치하는지 눈으로 확인
실측: `{"score":88,"crimePenalty":0.0,"cctvBonus":18.314569799,"lightBonus":0.0}` — 70-0+18.31+0=88.31 → 반올림 88, 일치 확인

- [x] **Step 3: 데이터가 거의 없을 법한 좌표로 점수 조회**

Run: `curl -s "http://localhost:8080/api/safety/score?lat=0.0&lng=-160.0"` (남태평양 한가운데)
Expected: HTTP 200, `{"score":70,"crimePenalty":0.0,"cctvBonus":0.0,"lightBonus":0.0}`
실측: `{"score":70,"crimePenalty":0.0,"cctvBonus":0.0,"lightBonus":0.0}` — 기대값과 정확히 일치

- [x] **Step 4: 파라미터 누락으로 400 확인**

Run: `curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/safety/score?lng=126.9780"`
Expected: `400`
실측: `400`

- [x] **Step 5: 범위 밖 좌표로 400 확인**

Run: `curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/safety/score?lat=91&lng=126.9780"`
Expected: `400`
실측: `400`

- [x] **Step 6: 애플리케이션 종료**

`bootRun` 프로세스 종료 (`netstat -ano`로 8080 포트 PID 확인 후 `taskkill //F //PID <pid>`)
실측: PID 1508 종료, 8080 포트 해제 확인
