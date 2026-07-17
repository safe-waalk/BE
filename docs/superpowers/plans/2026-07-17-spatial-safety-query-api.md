# 공간 조회 API (Safety Summary) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 좌표 하나를 받아 반경 내 CCTV/보안등/안심벨/관공서/범죄주의구역을 요약해 반환하는 `GET /api/safety/summary` 엔드포인트를 만든다.

**Architecture:** `com.safewalk.safety` 패키지에 `SafetyController` → `SafetyQueryService`(NamedParameterJdbcTemplate로 PostGIS 쿼리 5개 실행) → `dto` 레코드 3종. 별도 Repository 계층 없음, JPA/Hibernate Spatial 도입 없음.

**Tech Stack:** Spring Boot 4.0.7 (spring-boot-starter-webmvc, spring-boot-starter-jdbc), PostgreSQL + PostGIS (Supabase), JUnit 5 + AssertJ + Mockito (spring-boot-starter-*-test), Java 21 records.

## Global Constraints

- 패키지: `com.safewalk.safety` (컨트롤러/서비스), `com.safewalk.safety.dto` (DTO)
- ORM 도입 금지: `NamedParameterJdbcTemplate`/`JdbcTemplate`만 사용 (JPA/Hibernate Spatial 안 씀)
- 엔드포인트: `GET /api/safety/summary?lat={lat}&lng={lng}`
- 반경 상수(미터): cctv=150, securityLight=100, safetyBell=100, publicOffice=300, crimeZone=150
- 응답 필드명: `cctv`, `securityLight`, `safetyBell`, `publicOffice`(모두 `{count, nearestDistance}`), `crimeZone`(`{count, nearestDistance, maxGrade}`)
- 거리 단위는 미터, `geom::geography` 캐스팅으로 계산 (도(degree) 단위 아님)
- 검증: `lat` -90~90, `lng` -180~180, 누락 시 400. 전역 예외 핸들러 새로 만들지 않음 — `ResponseStatusException` 직접 사용
- 참고 스펙: `docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md`

---

### Task 1: 응답 DTO (records)

**Files:**
- Create: `src/main/java/com/safewalk/safety/dto/InfraSummary.java`
- Create: `src/main/java/com/safewalk/safety/dto/CrimeZoneSummary.java`
- Create: `src/main/java/com/safewalk/safety/dto/SafetySummaryResponse.java`
- Test: `src/test/java/com/safewalk/safety/dto/SafetySummaryResponseSerializationTest.java`

**Interfaces:**
- Produces: `InfraSummary(int count, Double nearestDistance)`, `CrimeZoneSummary(int count, Double nearestDistance, Integer maxGrade)`, `SafetySummaryResponse(InfraSummary cctv, InfraSummary securityLight, InfraSummary safetyBell, InfraSummary publicOffice, CrimeZoneSummary crimeZone)` — Task 2와 Task 3이 이 타입들을 그대로 사용.

- [x] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/dto/SafetySummaryResponseSerializationTest.java`:

```java
package com.safewalk.safety.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafetySummaryResponseSerializationTest {

    @Test
    void serializesWithExpectedFieldNames() throws Exception {
        SafetySummaryResponse response = new SafetySummaryResponse(
                new InfraSummary(12, 45.2),
                new InfraSummary(8, 20.1),
                new InfraSummary(1, 180.4),
                new InfraSummary(0, null),
                new CrimeZoneSummary(2, 60.0, 7)
        );

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"cctv\"");
        assertThat(json).contains("\"securityLight\"");
        assertThat(json).contains("\"safetyBell\"");
        assertThat(json).contains("\"publicOffice\"");
        assertThat(json).contains("\"crimeZone\"");
        assertThat(json).contains("\"nearestDistance\":45.2");
        assertThat(json).contains("\"maxGrade\":7");
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.dto.SafetySummaryResponseSerializationTest"`
Expected: FAIL (컴파일 에러 — `InfraSummary`, `CrimeZoneSummary`, `SafetySummaryResponse` 클래스가 없음)

- [x] **Step 3: DTO 구현**

`src/main/java/com/safewalk/safety/dto/InfraSummary.java`:

```java
package com.safewalk.safety.dto;

public record InfraSummary(int count, Double nearestDistance) {
}
```

`src/main/java/com/safewalk/safety/dto/CrimeZoneSummary.java`:

```java
package com.safewalk.safety.dto;

public record CrimeZoneSummary(int count, Double nearestDistance, Integer maxGrade) {
}
```

`src/main/java/com/safewalk/safety/dto/SafetySummaryResponse.java`:

```java
package com.safewalk.safety.dto;

public record SafetySummaryResponse(
        InfraSummary cctv,
        InfraSummary securityLight,
        InfraSummary safetyBell,
        InfraSummary publicOffice,
        CrimeZoneSummary crimeZone
) {
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.dto.SafetySummaryResponseSerializationTest"`
Expected: PASS

- [x] **Step 5: 커밋**

```bash
git add src/main/java/com/safewalk/safety/dto src/test/java/com/safewalk/safety/dto
git commit -m "feat: add safety summary response DTOs"
```

---

### Task 2: SafetyQueryService (PostGIS 쿼리)

**Files:**
- Create: `src/main/java/com/safewalk/safety/SafetyQueryService.java`
- Test: `src/test/java/com/safewalk/safety/SafetyQueryServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `InfraSummary`, `CrimeZoneSummary`, `SafetySummaryResponse` (Task 1)
- Produces: `SafetyQueryService.getSummary(double lat, double lng): SafetySummaryResponse` — Task 3(컨트롤러)이 이 메서드를 그대로 호출.

이 테스트는 실제 Supabase DB에 연결한다 (기존 `.env`/`application.properties` 설정 재사용, mock 없음). 읽기 전용 쿼리만 사용하므로 기존 데이터를 건드리지 않는다.

- [x] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/SafetyQueryServiceIntegrationTest.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.SafetySummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SafetyQueryServiceIntegrationTest {

    @Autowired
    private SafetyQueryService safetyQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsEmptySummaryForPointFarFromAllData() {
        // 남태평양 한가운데 좌표 — 실데이터가 있을 수 없는 지점
        SafetySummaryResponse response = safetyQueryService.getSummary(0.0, -160.0);

        assertThat(response.cctv().count()).isZero();
        assertThat(response.cctv().nearestDistance()).isNull();
        assertThat(response.securityLight().count()).isZero();
        assertThat(response.safetyBell().count()).isZero();
        assertThat(response.publicOffice().count()).isZero();
        assertThat(response.crimeZone().count()).isZero();
        assertThat(response.crimeZone().maxGrade()).isNull();
    }

    @Test
    void findsRealCctvRowAtItsExactCoordinate() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT ST_Y(geom) AS lat, ST_X(geom) AS lng FROM cctv LIMIT 1");
        double lat = ((Number) row.get("lat")).doubleValue();
        double lng = ((Number) row.get("lng")).doubleValue();

        SafetySummaryResponse response = safetyQueryService.getSummary(lat, lng);

        assertThat(response.cctv().count()).isGreaterThanOrEqualTo(1);
        assertThat(response.cctv().nearestDistance()).isLessThan(1.0);
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyQueryServiceIntegrationTest"`
Expected: FAIL (컴파일 에러 — `SafetyQueryService` 클래스가 없음)

- [x] **Step 3: 서비스 구현**

`src/main/java/com/safewalk/safety/SafetyQueryService.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.CrimeZoneSummary;
import com.safewalk.safety.dto.InfraSummary;
import com.safewalk.safety.dto.SafetySummaryResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;

@Service
public class SafetyQueryService {

    private static final double CCTV_RADIUS_M = 150.0;
    private static final double SECURITY_LIGHT_RADIUS_M = 100.0;
    private static final double SAFETY_BELL_RADIUS_M = 100.0;
    private static final double PUBLIC_OFFICE_RADIUS_M = 300.0;
    private static final double CRIME_ZONE_RADIUS_M = 150.0;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SafetyQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SafetySummaryResponse getSummary(double lat, double lng) {
        return new SafetySummaryResponse(
                queryInfra("cctv", lat, lng, CCTV_RADIUS_M),
                queryInfra("security_light", lat, lng, SECURITY_LIGHT_RADIUS_M),
                queryInfra("safety_bell", lat, lng, SAFETY_BELL_RADIUS_M),
                queryInfra("public_office", lat, lng, PUBLIC_OFFICE_RADIUS_M),
                queryCrimeZone(lat, lng, CRIME_ZONE_RADIUS_M)
        );
    }

    private InfraSummary queryInfra(String table, double lat, double lng, double radiusMeters) {
        // table은 이 클래스 내부 상수 호출로만 전달되며 외부 입력을 받지 않으므로 SQL Injection 위험 없음
        String sql = "SELECT COUNT(*) AS cnt, "
                + "MIN(ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)) AS nearest "
                + "FROM " + table + " "
                + "WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radius", radiusMeters);

        return jdbcTemplate.queryForObject(sql, params, (ResultSet rs, int rowNum) -> new InfraSummary(
                rs.getInt("cnt"),
                rs.getObject("nearest") == null ? null : rs.getDouble("nearest")
        ));
    }

    private CrimeZoneSummary queryCrimeZone(double lat, double lng, double radiusMeters) {
        String sql = "SELECT COUNT(*) AS cnt, "
                + "MIN(ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)) AS nearest, "
                + "MAX(grade) AS max_grade "
                + "FROM crime_zone "
                + "WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radius", radiusMeters);

        return jdbcTemplate.queryForObject(sql, params, (ResultSet rs, int rowNum) -> new CrimeZoneSummary(
                rs.getInt("cnt"),
                rs.getObject("nearest") == null ? null : rs.getDouble("nearest"),
                rs.getObject("max_grade") == null ? null : rs.getInt("max_grade")
        ));
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyQueryServiceIntegrationTest"`
Expected: PASS (실제 Supabase DB에 연결되어 두 테스트 모두 통과)

- [x] **Step 5: 커밋**

```bash
git add src/main/java/com/safewalk/safety/SafetyQueryService.java src/test/java/com/safewalk/safety/SafetyQueryServiceIntegrationTest.java
git commit -m "feat: add SafetyQueryService with PostGIS radius queries"
```

---

### Task 3: SafetyController (HTTP 엔드포인트)

**Files:**
- Create: `src/main/java/com/safewalk/safety/SafetyController.java`
- Test: `src/test/java/com/safewalk/safety/SafetyControllerTest.java`

**Interfaces:**
- Consumes: `SafetyQueryService.getSummary(double lat, double lng): SafetySummaryResponse` (Task 2)
- Produces: `GET /api/safety/summary?lat={lat}&lng={lng}` HTTP 엔드포인트

- [x] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/SafetyControllerTest.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.CrimeZoneSummary;
import com.safewalk.safety.dto.InfraSummary;
import com.safewalk.safety.dto.SafetySummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SafetyController.class)
class SafetyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SafetyQueryService safetyQueryService;

    @Test
    void missingLatReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/summary").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/summary").param("lat", "91").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestReturnsSummary() throws Exception {
        SafetySummaryResponse mockResponse = new SafetySummaryResponse(
                new InfraSummary(12, 45.2),
                new InfraSummary(8, 20.1),
                new InfraSummary(1, 180.4),
                new InfraSummary(0, null),
                new CrimeZoneSummary(2, 60.0, 7)
        );
        when(safetyQueryService.getSummary(anyDouble(), anyDouble())).thenReturn(mockResponse);

        mockMvc.perform(get("/api/safety/summary").param("lat", "37.5665").param("lng", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cctv.count").value(12))
                .andExpect(jsonPath("$.crimeZone.maxGrade").value(7));
    }
}
```

- [x] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyControllerTest"`
Expected: FAIL (컴파일 에러 — `SafetyController` 클래스가 없음)

- [x] **Step 3: 컨트롤러 구현**

`src/main/java/com/safewalk/safety/SafetyController.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.SafetySummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SafetyController {

    private final SafetyQueryService safetyQueryService;

    public SafetyController(SafetyQueryService safetyQueryService) {
        this.safetyQueryService = safetyQueryService;
    }

    @GetMapping("/api/safety/summary")
    public SafetySummaryResponse getSummary(@RequestParam double lat, @RequestParam double lng) {
        if (lat < -90 || lat > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat must be between -90 and 90");
        }
        if (lng < -180 || lng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lng must be between -180 and 180");
        }
        return safetyQueryService.getSummary(lat, lng);
    }
}
```

- [x] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyControllerTest"`
Expected: PASS

- [x] **Step 5: 커밋**

```bash
git add src/main/java/com/safewalk/safety/SafetyController.java src/test/java/com/safewalk/safety/SafetyControllerTest.java
git commit -m "feat: add GET /api/safety/summary endpoint"
```

---

### Task 4: 수동 End-to-End 검증

**Files:** 없음 (코드 변경 없음, 실행 중인 앱에 대한 수동 확인)

**Interfaces:**
- Consumes: `GET /api/safety/summary?lat={lat}&lng={lng}` (Task 3)

- [x] **Step 1: 애플리케이션 기동**

Run: `./gradlew bootRun`
Expected: 로그에 `Started SafewalkApplication` 출력

- [x] **Step 2: 정상 좌표로 호출**

Run: `curl -s "http://localhost:8080/api/safety/summary?lat=37.5665&lng=126.9780"`
Expected: HTTP 200, `cctv`/`securityLight`/`safetyBell`/`publicOffice`/`crimeZone` 키를 가진 JSON. `publicOffice.count`는 0 (데이터 미적재 상태이므로 정상).
실측: `{"cctv":{"count":7,"nearestDistance":66.85},"securityLight":{"count":0,"nearestDistance":null},"safetyBell":{"count":1,"nearestDistance":66.80},"publicOffice":{"count":0,"nearestDistance":null},"crimeZone":{"count":0,"nearestDistance":null,"maxGrade":null}}` (200 OK)

- [x] **Step 3: 파라미터 누락으로 호출**

Run: `curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/safety/summary?lng=126.9780"`
Expected: `400`

- [x] **Step 4: 범위 밖 좌표로 호출**

Run: `curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/safety/summary?lat=91&lng=126.9780"`
Expected: `400`

- [x] **Step 5: 애플리케이션 종료**

`bootRun` 프로세스 종료 (Ctrl+C 또는 background 실행 시 `taskkill //F //IM java.exe`)
