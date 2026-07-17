# publicOffice 제거 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/safety/summary` 응답과 관련 코드/스키마 문서에서 `publicOffice`(관공서) 데이터를 완전히 제거한다.

**Architecture:** `SafetySummaryResponse` 레코드의 필드 수를 5개→4개로 줄이고, `SafetyQueryService`에서 관공서 쿼리 호출을 제거한다. 이 변경이 테스트 3종에 동시에 영향을 주므로 Task 1에서 한 번에 처리한다. `HealthController`, `db/schema.sql`, 설계/계획 문서는 각각 독립적인 후속 Task로 정리한다.

**Tech Stack:** Spring Boot 4.0.7, JUnit 5 + AssertJ + Mockito, Java 21 records.

## Global Constraints

- 실제 Supabase DB의 `public_office` 테이블은 건드리지 않는다 (DROP 금지) — `docs/superpowers/specs/2026-07-18-remove-public-office-design.md` 참고
- API breaking change에 대한 버저닝/deprecation 처리 없음 — 즉시 필드 제거
- 참고 스펙: `docs/superpowers/specs/2026-07-18-remove-public-office-design.md`

---

### Task 1: DTO/Service에서 publicOffice 제거

**Files:**
- Modify: `src/main/java/com/safewalk/safety/dto/SafetySummaryResponse.java`
- Modify: `src/main/java/com/safewalk/safety/SafetyQueryService.java`
- Modify: `src/test/java/com/safewalk/safety/dto/SafetySummaryResponseSerializationTest.java`
- Modify: `src/test/java/com/safewalk/safety/SafetyQueryServiceIntegrationTest.java`
- Modify: `src/test/java/com/safewalk/safety/SafetyControllerTest.java`

**Interfaces:**
- Produces: `SafetySummaryResponse(InfraSummary cctv, InfraSummary securityLight, InfraSummary safetyBell, CrimeZoneSummary crimeZone)` (4-arg, publicOffice 없음) — Task 2 이후 어떤 코드도 이 시그니처를 참조하지 않으므로 영향 범위는 이 Task로 한정된다.

이 레코드는 3개 테스트 파일에서 동시에 생성자로 사용되므로, 필드를 제거하면 세 파일 모두 즉시 컴파일 에러가 난다. 테스트를 먼저 4-arg로 고쳐서 컴파일 에러(RED)를 만들고, 프로덕션 코드를 고쳐서 통과(GREEN)시킨다.

- [ ] **Step 1: 테스트 3종을 4-arg 시그니처로 수정**

`src/test/java/com/safewalk/safety/dto/SafetySummaryResponseSerializationTest.java` 전체를 다음으로 교체:

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
                new CrimeZoneSummary(2, 60.0, 7)
        );

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"cctv\"");
        assertThat(json).contains("\"securityLight\"");
        assertThat(json).contains("\"safetyBell\"");
        assertThat(json).doesNotContain("\"publicOffice\"");
        assertThat(json).contains("\"crimeZone\"");
        assertThat(json).contains("\"nearestDistance\":45.2");
        assertThat(json).contains("\"maxGrade\":7");
    }
}
```

`src/test/java/com/safewalk/safety/SafetyQueryServiceIntegrationTest.java`에서 `assertThat(response.publicOffice().count()).isZero();` 줄(현재 31번째 줄)만 삭제. 그 외 내용은 그대로 둔다. 수정 후 전체 파일:

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

`src/test/java/com/safewalk/safety/SafetyControllerTest.java` 전체를 다음으로 교체 (mock 생성자에서 `new InfraSummary(0, null)` publicOffice 인자 제거):

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

- [ ] **Step 2: 컴파일 에러(RED) 확인**

Run: `./gradlew compileTestJava`
Expected: FAIL — `SafetySummaryResponse`의 생성자가 5개 인자를 요구하는데 테스트가 4개만 넘겨서 컴파일 에러

- [ ] **Step 3: DTO에서 publicOffice 필드 제거**

`src/main/java/com/safewalk/safety/dto/SafetySummaryResponse.java` 전체를 다음으로 교체:

```java
package com.safewalk.safety.dto;

public record SafetySummaryResponse(
        InfraSummary cctv,
        InfraSummary securityLight,
        InfraSummary safetyBell,
        CrimeZoneSummary crimeZone
) {
}
```

- [ ] **Step 4: SafetyQueryService에서 관공서 쿼리 제거**

`src/main/java/com/safewalk/safety/SafetyQueryService.java` 전체를 다음으로 교체 (`PUBLIC_OFFICE_RADIUS_M` 상수와 `queryInfra("public_office", ...)` 호출 삭제):

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

- [ ] **Step 5: 테스트 통과(GREEN) 확인**

Run: `./gradlew test --rerun-tasks`
Expected: PASS (전체 테스트, 실제 Supabase DB 연동 포함)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/safewalk/safety/dto/SafetySummaryResponse.java src/main/java/com/safewalk/safety/SafetyQueryService.java src/test/java/com/safewalk/safety/dto/SafetySummaryResponseSerializationTest.java src/test/java/com/safewalk/safety/SafetyQueryServiceIntegrationTest.java src/test/java/com/safewalk/safety/SafetyControllerTest.java
git commit -m "refactor: remove publicOffice from safety summary API"
```

---

### Task 2: HealthController에서 public_office 제외

**Files:**
- Modify: `src/main/java/com/safewalk/HealthController.java`

**Interfaces:** 없음 (다른 Task와 의존 관계 없음)

- [ ] **Step 1: 테이블 목록에서 public_office 제거**

`src/main/java/com/safewalk/HealthController.java`의 27번째 줄:

```java
		String[] tables = {"crime_zone", "cctv", "security_light", "safety_bell", "public_office", "report"};
```

다음으로 교체:

```java
		String[] tables = {"crime_zone", "cctv", "security_light", "safety_bell", "report"};
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add src/main/java/com/safewalk/HealthController.java
git commit -m "refactor: drop public_office from health check table counts"
```

---

### Task 3: db/schema.sql에서 public_office 테이블 정의 제거

**Files:**
- Modify: `db/schema.sql`

**Interfaces:** 없음 (문서 성격의 스키마 파일, 실행되지 않음 — 실제 Supabase DB는 변경하지 않는다)

- [ ] **Step 1: public_office 테이블/인덱스 정의 삭제**

`db/schema.sql`에서 35~43번째 줄(`-- ⑤ 관공서 ...` 주석과 `CREATE TABLE public_office (...)` 블록 전체)을 삭제하고, 63번째 줄(`CREATE INDEX idx_office_geom ON public_office USING GIST(geom);`)을 삭제. 수정 후 전체 파일:

```sql
-- Safe-walk 데이터베이스 스키마
-- 작업계획서(Safe-walk_백엔드_작업계획서.md) 1단계 기준
-- 실행: psql -d safewalk -f BE/db/schema.sql

-- ① 범죄 위험 구역 (crime_zones.csv)
CREATE TABLE crime_zone (
    id BIGSERIAL PRIMARY KEY,
    gid INT,                          -- 원본 구역 ID
    grade INT NOT NULL,               -- 위험 등급 0~10
    geom GEOMETRY(Point, 4326)         -- 좌표 (4326 = GPS 위경도 좌표계)
);

-- ② CCTV
CREATE TABLE cctv (
    id BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    camera_count INT,                 -- 한 지점에 카메라 여러 대일 수 있음
    geom GEOMETRY(Point, 4326)
);

-- ③ 보안등
CREATE TABLE security_light (
    id BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    geom GEOMETRY(Point, 4326)
);

-- ④ 안심벨
CREATE TABLE safety_bell (
    id BIGSERIAL PRIMARY KEY,
    address VARCHAR(300),
    geom GEOMETRY(Point, 4326)
);

-- ⑤ 사용자 신고
CREATE TABLE report (
    id BIGSERIAL PRIMARY KEY,
    content TEXT NOT NULL,            -- 사용자가 쓴 원문
    category VARCHAR(30),             -- LLM이 파싱한 카테고리 (LIGHTING 등)
    severity VARCHAR(10),             -- LLM이 파싱한 위험도 (HIGH/MID/LOW)
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING / APPROVED / REJECTED
    geom GEOMETRY(Point, 4326),
    created_at TIMESTAMP DEFAULT now()
);

-- 공간 인덱스 (GIST)
-- 이거 안 하면 반경 검색이 엄청 느려짐 (전체 테이블 다 뒤짐)
-- GIST 인덱스 = 좌표 전용 인덱스. "주변에 뭐 있어?" 검색을 빠르게 해줌.
CREATE INDEX idx_crime_zone_geom ON crime_zone USING GIST(geom);
CREATE INDEX idx_cctv_geom ON cctv USING GIST(geom);
CREATE INDEX idx_light_geom ON security_light USING GIST(geom);
CREATE INDEX idx_bell_geom ON safety_bell USING GIST(geom);
CREATE INDEX idx_report_geom ON report USING GIST(geom);
```

- [ ] **Step 2: 커밋**

```bash
git add db/schema.sql
git commit -m "docs: remove public_office table definition from schema.sql"
```

---

### Task 4: 설계/계획 문서 갱신

**Files:**
- Modify: `docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md`
- Modify: `docs/superpowers/plans/2026-07-17-spatial-safety-query-api.md`

**Interfaces:** 없음 (문서 전용, 코드에 영향 없음)

- [ ] **Step 1: 두 문서에서 publicOffice/public_office 언급을 찾아 제거하거나 "제거됨" 각주 추가**

Run: `grep -rn -i "public.office" docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md docs/superpowers/plans/2026-07-17-spatial-safety-query-api.md`

각 위치를 확인하고, 필드/반경 목록에서 publicOffice 항목을 삭제한다. 과거 진행 기록(이미 완료 표시된 체크박스, 실측 curl 결과 등 히스토리성 서술)은 그대로 두고 건드리지 않는다 — 스펙/계획의 "현재 사양"을 설명하는 부분만 수정 대상이다.

- [ ] **Step 2: 커밋**

```bash
git add docs/superpowers/specs/2026-07-17-spatial-safety-query-api-design.md docs/superpowers/plans/2026-07-17-spatial-safety-query-api.md
git commit -m "docs: update safety summary spec/plan to drop publicOffice"
```

---

### Task 5: 수동 End-to-End 검증

**Files:** 없음 (코드 변경 없음, 실행 중인 앱에 대한 수동 확인)

**Interfaces:**
- Consumes: `GET /api/safety/summary?lat={lat}&lng={lng}` (Task 1), `GET /api/health/db/counts` (Task 2)

- [ ] **Step 1: 애플리케이션 기동**

Run: `./gradlew bootRun` (백그라운드 실행)
Expected: 로그에 `Started SafewalkApplication` 출력

- [ ] **Step 2: Safety Summary 응답에 publicOffice 키가 없는지 확인**

Run: `curl -s "http://localhost:8080/api/safety/summary?lat=37.5665&lng=126.9780"`
Expected: HTTP 200, JSON에 `cctv`/`securityLight`/`safetyBell`/`crimeZone` 키만 존재하고 `publicOffice` 키는 없음

- [ ] **Step 3: 헬스체크 카운트 응답에 public_office 키가 없는지 확인**

Run: `curl -s "http://localhost:8080/api/health/db/counts"`
Expected: HTTP 200, JSON에 `crime_zone`/`cctv`/`security_light`/`safety_bell`/`report` 키만 존재하고 `public_office` 키는 없음

- [ ] **Step 4: 애플리케이션 종료**

`bootRun` 프로세스 종료 (`netstat -ano`로 8080 포트 PID 확인 후 `taskkill //F //PID <pid>`)
