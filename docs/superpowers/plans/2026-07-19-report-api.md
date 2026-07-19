# Report API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/reports` (신고 등록)과 `GET /api/reports` (최신순 목록 조회)를 기존 `report` 테이블을 그대로 사용해서 구현한다.

**Architecture:** `com.safewalk.report` 패키지에 `ReportController` (HTTP + 입력 검증)와 `ReportService` (DB, `NamedParameterJdbcTemplate`)를 추가한다. 기존 `com.safewalk.safety` 패키지 구조를 동일하게 따른다.

**Tech Stack:** Spring Boot 4.0.7, Java 21, Gradle, PostgreSQL + PostGIS, `NamedParameterJdbcTemplate`

## Global Constraints

- ORM 금지 — `NamedParameterJdbcTemplate` 직접 사용
- 전역 예외 핸들러 없음 — 컨트롤러에서 `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` 인라인으로 던짐
- DTO는 Java 21 record
- 스키마 변경 없음 — 기존 `report` 테이블 그대로 사용
- `severity` 허용값: `HIGH`, `MEDIUM`, `LOW`
- `category` 허용값: `LIGHTING`, `CCTV`, `SUSPICIOUS_AREA`, `ROAD_ENVIRONMENT`, `CRIME_RISK`, `NOISE_GROUP`, `WOMEN_SAFETY`, `FALSE_REPORT`, `PRIVACY_RISK`, `ETC`
- 테스트 실행: `./gradlew test`
- 작업 브랜치: `TW`
- 커밋 메시지에 `Co-Authored-By` / `Claude-Session` 트레일러 금지 (`CLAUDE.md` 참고)

---

## File Map

| 파일 | 작업 | 역할 |
|---|---|---|
| `src/main/java/com/safewalk/report/dto/ReportRequest.java` | 생성 | POST 요청 바디 DTO |
| `src/main/java/com/safewalk/report/dto/ReportResponse.java` | 생성 | POST/GET 응답 DTO |
| `src/main/java/com/safewalk/report/ReportService.java` | 생성 | DB 저장/조회 |
| `src/main/java/com/safewalk/report/ReportController.java` | 생성 | HTTP 처리 + 입력 검증 |
| `src/test/java/com/safewalk/report/ReportControllerTest.java` | 생성 | `@WebMvcTest` 단위 테스트 |
| `src/test/java/com/safewalk/report/ReportServiceIntegrationTest.java` | 생성 | `@SpringBootTest` 실DB 통합 테스트 |

---

### Task 1: POST /api/reports

**Files:**
- Create: `src/main/java/com/safewalk/report/dto/ReportRequest.java`
- Create: `src/main/java/com/safewalk/report/dto/ReportResponse.java`
- Create: `src/main/java/com/safewalk/report/ReportService.java`
- Create: `src/main/java/com/safewalk/report/ReportController.java`
- Create: `src/test/java/com/safewalk/report/ReportControllerTest.java` (POST 케이스)

**Interfaces:**
- Produces: `ReportService.save(ReportRequest request) → ReportResponse` (Task 2에서 사용)
- Produces: `POST /api/reports` → HTTP 201 + `ReportResponse` JSON

- [ ] **Step 1: Write failing controller tests for POST**

`src/test/java/com/safewalk/report/ReportControllerTest.java` 파일 생성:

```java
package com.safewalk.report;

import com.safewalk.report.dto.ReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @Test
    void blankContentReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"","lat":37.5,"lng":127.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":91.0,"lng":127.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lngOutOfRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":37.5,"lng":181.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCategoryReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":37.5,"lng":127.0,"category":"BLAH"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidSeverityReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":37.5,"lng":127.0,"severity":"CRITICAL"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validPostReturns201WithBody() throws Exception {
        when(reportService.save(any())).thenReturn(new ReportResponse(
                1L, "골목 가로등 없음", "LIGHTING", "HIGH", "PENDING",
                37.5665, 126.9780, LocalDateTime.of(2026, 7, 19, 12, 0)));

        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"골목 가로등 없음","lat":37.5665,"lng":126.9780,"category":"LIGHTING","severity":"HIGH"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.category").value("LIGHTING"))
                .andExpect(jsonPath("$.severity").value("HIGH"));
    }
}
```

- [ ] **Step 2: Run — compile 실패 확인 (클래스 미존재)**

```bash
./gradlew test --tests "com.safewalk.report.ReportControllerTest"
```

Expected: BUILD FAILED — `ReportController`, `ReportService`, `ReportResponse` cannot find symbol

- [ ] **Step 3: Create DTOs**

`src/main/java/com/safewalk/report/dto/ReportRequest.java`:

```java
package com.safewalk.report.dto;

public record ReportRequest(String content, double lat, double lng, String category, String severity) {
}
```

`src/main/java/com/safewalk/report/dto/ReportResponse.java`:

```java
package com.safewalk.report.dto;

import java.time.LocalDateTime;

public record ReportResponse(long id, String content, String category, String severity,
                              String status, double lat, double lng, LocalDateTime createdAt) {
}
```

- [ ] **Step 4: Create ReportService (save 구현, findAll은 stub)**

`src/main/java/com/safewalk/report/ReportService.java`:

```java
package com.safewalk.report;

import com.safewalk.report.dto.ReportRequest;
import com.safewalk.report.dto.ReportResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class ReportService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReportService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReportResponse save(ReportRequest request) {
        String sql = """
                INSERT INTO report (content, category, severity, geom)
                VALUES (:content, :category, :severity, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
                RETURNING id, content, category, severity, status,
                          ST_Y(geom) AS lat, ST_X(geom) AS lng, created_at
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("content", request.content())
                .addValue("category", request.category())
                .addValue("severity", request.severity())
                .addValue("lat", request.lat())
                .addValue("lng", request.lng());
        return jdbcTemplate.queryForObject(sql, params, ReportService::mapRow);
    }

    public List<ReportResponse> findAll() {
        throw new UnsupportedOperationException("implemented in Task 2");
    }

    private static ReportResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReportResponse(
                rs.getLong("id"),
                rs.getString("content"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
```

- [ ] **Step 5: Create ReportController (POST 구현, GET은 stub)**

`src/main/java/com/safewalk/report/ReportController.java`:

```java
package com.safewalk.report;

import com.safewalk.report.dto.ReportRequest;
import com.safewalk.report.dto.ReportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
public class ReportController {

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "LIGHTING", "CCTV", "SUSPICIOUS_AREA", "ROAD_ENVIRONMENT",
            "CRIME_RISK", "NOISE_GROUP", "WOMEN_SAFETY", "FALSE_REPORT",
            "PRIVACY_RISK", "ETC"
    );
    private static final Set<String> VALID_SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse createReport(@RequestBody ReportRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        if (request.lat() < -90 || request.lat() > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat must be between -90 and 90");
        }
        if (request.lng() < -180 || request.lng() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lng must be between -180 and 180");
        }
        if (request.category() != null && !VALID_CATEGORIES.contains(request.category())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid category: " + request.category());
        }
        if (request.severity() != null && !VALID_SEVERITIES.contains(request.severity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "severity must be HIGH, MEDIUM, or LOW");
        }
        return reportService.save(request);
    }

    @GetMapping("/api/reports")
    public List<ReportResponse> listReports() {
        throw new UnsupportedOperationException("implemented in Task 2");
    }
}
```

- [ ] **Step 6: Run POST tests — 전부 PASS 확인**

```bash
./gradlew test --tests "com.safewalk.report.ReportControllerTest"
```

Expected: 6개 테스트 모두 PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/safewalk/report/ src/test/java/com/safewalk/report/ReportControllerTest.java
git commit -m "feat: add POST /api/reports with validation"
```

---

### Task 2: GET /api/reports

**Files:**
- Modify: `src/main/java/com/safewalk/report/ReportService.java` — `findAll()` stub을 실구현으로 교체
- Modify: `src/main/java/com/safewalk/report/ReportController.java` — `listReports()` stub을 실구현으로 교체
- Modify: `src/test/java/com/safewalk/report/ReportControllerTest.java` — GET 테스트 추가

**Interfaces:**
- Consumes: `ReportResponse(long id, String content, String category, String severity, String status, double lat, double lng, LocalDateTime createdAt)` (Task 1 정의)
- Produces: `ReportService.findAll() → List<ReportResponse>`, `GET /api/reports` → HTTP 200 + JSON 배열

- [ ] **Step 1: Add failing GET test to ReportControllerTest**

`src/test/java/com/safewalk/report/ReportControllerTest.java` 클래스 끝 (마지막 `}` 바로 위)에 추가:

```java
    @Test
    void getReportsReturns200WithList() throws Exception {
        when(reportService.findAll()).thenReturn(List.of(
                new ReportResponse(1L, "테스트 신고", "LIGHTING", "HIGH", "PENDING",
                        37.5, 127.0, LocalDateTime.of(2026, 7, 19, 12, 0))
        ));
        mockMvc.perform(get("/api/reports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].category").value("LIGHTING"));
    }
```

- [ ] **Step 2: Run GET test — FAIL 확인 (stub이 예외 던짐)**

```bash
./gradlew test --tests "com.safewalk.report.ReportControllerTest#getReportsReturns200WithList"
```

Expected: FAIL — 500 Internal Server Error (`UnsupportedOperationException`)

- [ ] **Step 3: Implement ReportService.findAll()**

`src/main/java/com/safewalk/report/ReportService.java` — `findAll()` 메서드를 아래로 교체:

```java
    public List<ReportResponse> findAll() {
        String sql = """
                SELECT id, content, category, severity, status,
                       ST_Y(geom) AS lat, ST_X(geom) AS lng, created_at
                FROM report
                ORDER BY created_at DESC
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource(), ReportService::mapRow);
    }
```

- [ ] **Step 4: Implement ReportController.listReports()**

`src/main/java/com/safewalk/report/ReportController.java` — `listReports()` 메서드를 아래로 교체:

```java
    @GetMapping("/api/reports")
    public List<ReportResponse> listReports() {
        return reportService.findAll();
    }
```

- [ ] **Step 5: Run all controller tests — 전부 PASS 확인**

```bash
./gradlew test --tests "com.safewalk.report.ReportControllerTest"
```

Expected: 7개 테스트 모두 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/safewalk/report/ReportService.java \
        src/main/java/com/safewalk/report/ReportController.java \
        src/test/java/com/safewalk/report/ReportControllerTest.java
git commit -m "feat: add GET /api/reports"
```

---

### Task 3: Integration tests

**Files:**
- Create: `src/test/java/com/safewalk/report/ReportServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `ReportService.save(ReportRequest)` — `ReportRequest(String content, double lat, double lng, String category, String severity)`
- Consumes: `ReportService.findAll() → List<ReportResponse>`
- Consumes: 실 PostgreSQL + PostGIS DB (`SafetyQueryServiceIntegrationTest`와 동일 환경)

**Prerequisites:** 실 DB가 실행 중이어야 합니다. `docker-compose up -d`로 확인 후 기존 `SafetyQueryServiceIntegrationTest`가 통과하는 상태면 동일하게 동작합니다.

- [ ] **Step 1: Write integration tests**

`src/test/java/com/safewalk/report/ReportServiceIntegrationTest.java`:

```java
package com.safewalk.report;

import com.safewalk.report.dto.ReportRequest;
import com.safewalk.report.dto.ReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReportServiceIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Test
    void savedReportAppearsInList() {
        ReportRequest request = new ReportRequest("골목 가로등 없음", 37.5665, 126.9780, "LIGHTING", "HIGH");
        ReportResponse saved = reportService.save(request);

        assertThat(saved.id()).isPositive();
        assertThat(saved.content()).isEqualTo("골목 가로등 없음");
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(saved.category()).isEqualTo("LIGHTING");
        assertThat(saved.severity()).isEqualTo("HIGH");

        List<ReportResponse> all = reportService.findAll();
        assertThat(all).anyMatch(r -> r.id() == saved.id());
    }

    @Test
    void nullCategoryAndSeverityAreAllowed() {
        ReportRequest request = new ReportRequest("설명만 있음", 37.5, 127.0, null, null);
        ReportResponse saved = reportService.save(request);

        assertThat(saved.category()).isNull();
        assertThat(saved.severity()).isNull();
        assertThat(saved.status()).isEqualTo("PENDING");
    }

    @Test
    void latLngRoundTripFromGeom() {
        double lat = 37.123456;
        double lng = 127.654321;
        ReportRequest request = new ReportRequest("좌표 테스트", lat, lng, null, null);
        ReportResponse saved = reportService.save(request);

        assertThat(saved.lat()).isEqualTo(lat);
        assertThat(saved.lng()).isEqualTo(lng);
    }
}
```

- [ ] **Step 2: Run integration tests against real DB**

```bash
./gradlew test --tests "com.safewalk.report.ReportServiceIntegrationTest"
```

Expected: 3개 테스트 모두 PASS

- [ ] **Step 3: Run full test suite**

```bash
./gradlew test
```

Expected: 전체 PASS (기존 `SafetyControllerTest`, `SafetyQueryServiceIntegrationTest` 포함)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/safewalk/report/ReportServiceIntegrationTest.java
git commit -m "test: add ReportService integration tests"
```
