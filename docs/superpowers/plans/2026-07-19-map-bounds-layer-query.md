# 지도 bounds 기반 레이어 조회 API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 지도 SDK의 bounds(남서/북동 좌표)를 받아 그 영역 안의 CCTV/보안등/안심벨/범죄구역 개별 좌표를 레이어별로 반환하는 `GET /api/safety/layers` 엔드포인트를 만든다.

**Architecture:** `com.safewalk.safety` 패키지에 기존 `SafetyController`/`SafetyQueryService`와 완전히 분리된 `SafetyLayerController`/`SafetyLayerQueryService`를 신설한다. 컨트롤러가 모든 검증(좌표 범위, 박스 유효성, bounds 크기, layers 값)을 담당하고, 서비스는 검증된 입력만 받아 `layers`에 포함된 테이블만 쿼리한다.

**Tech Stack:** Spring Boot 4.0.7, JUnit 5 + AssertJ + Mockito, Java 21 records, Jackson `@JsonInclude`.

## Global Constraints

- 패키지: `com.safewalk.safety` (컨트롤러/서비스), `com.safewalk.safety.dto` (DTO)
- ORM 도입 금지: `NamedParameterJdbcTemplate`만 사용 (JPA/Hibernate Spatial 안 씀)
- 엔드포인트: `GET /api/safety/layers?swLat={}&swLng={}&neLat={}&neLng={}&layers={csv}`
- 대상 테이블: `cctv`, `security_light`, `safety_bell`, `crime_zone`
- 레이어당 응답 개수 상한: SQL `LIMIT 500`
- bounds 크기 상한: 가로/세로 어느 한 변이라도 5,000m 초과 시 400 (위도 1도 ≈ 111,320m, 경도는 평균 위도 코사인 보정)
- 응답은 요청한 레이어 키만 포함 (`@JsonInclude(JsonInclude.Include.NON_NULL)`로 미요청 레이어는 `null` → 직렬화에서 키 생략)
- 검증: 전역 예외 핸들러 신설 안 함 — `ResponseStatusException` 직접 사용 (기존 `SafetyController` 패턴)
- 참고 스펙: `docs/superpowers/specs/2026-07-19-map-bounds-layer-query-design.md`

---

### Task 1: 응답 DTO (records)

**Files:**
- Create: `src/main/java/com/safewalk/safety/dto/CctvPoint.java`
- Create: `src/main/java/com/safewalk/safety/dto/SecurityLightPoint.java`
- Create: `src/main/java/com/safewalk/safety/dto/SafetyBellPoint.java`
- Create: `src/main/java/com/safewalk/safety/dto/CrimeZonePoint.java`
- Create: `src/main/java/com/safewalk/safety/dto/SafetyLayerResponse.java`
- Test: `src/test/java/com/safewalk/safety/dto/SafetyLayerResponseSerializationTest.java`

**Interfaces:**
- Produces: `CctvPoint(long id, double lat, double lng, String address, int cameraCount)`, `SecurityLightPoint(long id, double lat, double lng, String address)`, `SafetyBellPoint(long id, double lat, double lng, String address)`, `CrimeZonePoint(long id, double lat, double lng, int grade)`, `SafetyLayerResponse(List<CctvPoint> cctv, List<SecurityLightPoint> securityLight, List<SafetyBellPoint> safetyBell, List<CrimeZonePoint> crimeZone)` — Task 2와 Task 3이 이 타입들을 그대로 사용.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/dto/SafetyLayerResponseSerializationTest.java`:

```java
package com.safewalk.safety.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyLayerResponseSerializationTest {

    @Test
    void omitsKeysForLayersNotRequested() throws Exception {
        SafetyLayerResponse response = new SafetyLayerResponse(
                List.of(new CctvPoint(1, 37.5665, 126.9780, "서울 중구", 2)),
                null,
                null,
                List.of(new CrimeZonePoint(3, 37.5550, 126.9700, 7))
        );

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"cctv\"");
        assertThat(json).contains("\"crimeZone\"");
        assertThat(json).doesNotContain("\"securityLight\"");
        assertThat(json).doesNotContain("\"safetyBell\"");
        assertThat(json).contains("\"cameraCount\":2");
        assertThat(json).contains("\"grade\":7");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.dto.SafetyLayerResponseSerializationTest"`
Expected: FAIL (컴파일 에러 — `CctvPoint`, `CrimeZonePoint`, `SafetyLayerResponse` 클래스가 없음)

- [ ] **Step 3: DTO 구현**

`src/main/java/com/safewalk/safety/dto/CctvPoint.java`:

```java
package com.safewalk.safety.dto;

public record CctvPoint(long id, double lat, double lng, String address, int cameraCount) {
}
```

`src/main/java/com/safewalk/safety/dto/SecurityLightPoint.java`:

```java
package com.safewalk.safety.dto;

public record SecurityLightPoint(long id, double lat, double lng, String address) {
}
```

`src/main/java/com/safewalk/safety/dto/SafetyBellPoint.java`:

```java
package com.safewalk.safety.dto;

public record SafetyBellPoint(long id, double lat, double lng, String address) {
}
```

`src/main/java/com/safewalk/safety/dto/CrimeZonePoint.java`:

```java
package com.safewalk.safety.dto;

public record CrimeZonePoint(long id, double lat, double lng, int grade) {
}
```

`src/main/java/com/safewalk/safety/dto/SafetyLayerResponse.java`:

```java
package com.safewalk.safety.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SafetyLayerResponse(
        List<CctvPoint> cctv,
        List<SecurityLightPoint> securityLight,
        List<SafetyBellPoint> safetyBell,
        List<CrimeZonePoint> crimeZone
) {
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.dto.SafetyLayerResponseSerializationTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/safewalk/safety/dto/CctvPoint.java src/main/java/com/safewalk/safety/dto/SecurityLightPoint.java src/main/java/com/safewalk/safety/dto/SafetyBellPoint.java src/main/java/com/safewalk/safety/dto/CrimeZonePoint.java src/main/java/com/safewalk/safety/dto/SafetyLayerResponse.java src/test/java/com/safewalk/safety/dto/SafetyLayerResponseSerializationTest.java
git commit -m "feat: add safety layer response DTOs"
```

---

### Task 2: SafetyLayerQueryService (PostGIS bounds 쿼리)

**Files:**
- Create: `src/main/java/com/safewalk/safety/SafetyLayerQueryService.java`
- Test: `src/test/java/com/safewalk/safety/SafetyLayerQueryServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `CctvPoint`, `SecurityLightPoint`, `SafetyBellPoint`, `CrimeZonePoint`, `SafetyLayerResponse` (Task 1)
- Produces: `SafetyLayerQueryService.getLayers(double swLat, double swLng, double neLat, double neLng, Set<String> layers): SafetyLayerResponse` — Task 3(컨트롤러)이 이 메서드를 그대로 호출. `layers`에 포함되지 않은 키는 결과 레코드에서 `null`.

이 테스트는 실제 Supabase DB에 연결한다 (기존 `.env`/`application.properties` 설정 재사용, mock 없음). 읽기 전용 쿼리만 사용하므로 기존 데이터를 건드리지 않는다. bounds 크기 검증은 이 서비스의 책임이 아니다 (Task 3 컨트롤러가 담당) — 테스트에서 임의의 좁은 bounds를 그대로 사용해도 된다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/SafetyLayerQueryServiceIntegrationTest.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.SafetyLayerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SafetyLayerQueryServiceIntegrationTest {

    @Autowired
    private SafetyLayerQueryService safetyLayerQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void onlyQueriesRequestedLayers() {
        SafetyLayerResponse response = safetyLayerQueryService.getLayers(
                -1.0, -161.0, 1.0, -159.0, Set.of("cctv"));

        assertThat(response.cctv()).isNotNull();
        assertThat(response.securityLight()).isNull();
        assertThat(response.safetyBell()).isNull();
        assertThat(response.crimeZone()).isNull();
    }

    @Test
    void findsRealCctvPointWithinBounds() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT ST_Y(geom) AS lat, ST_X(geom) AS lng FROM cctv LIMIT 1");
        double lat = ((Number) row.get("lat")).doubleValue();
        double lng = ((Number) row.get("lng")).doubleValue();

        SafetyLayerResponse response = safetyLayerQueryService.getLayers(
                lat - 0.01, lng - 0.01, lat + 0.01, lng + 0.01, Set.of("cctv"));

        assertThat(response.cctv()).isNotEmpty();
        assertThat(response.securityLight()).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyLayerQueryServiceIntegrationTest"`
Expected: FAIL (컴파일 에러 — `SafetyLayerQueryService` 클래스가 없음)

- [ ] **Step 3: 서비스 구현**

`src/main/java/com/safewalk/safety/SafetyLayerQueryService.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.CctvPoint;
import com.safewalk.safety.dto.CrimeZonePoint;
import com.safewalk.safety.dto.SafetyBellPoint;
import com.safewalk.safety.dto.SafetyLayerResponse;
import com.safewalk.safety.dto.SecurityLightPoint;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

@Service
public class SafetyLayerQueryService {

    private static final int LAYER_POINT_LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SafetyLayerQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SafetyLayerResponse getLayers(double swLat, double swLng, double neLat, double neLng, Set<String> layers) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("swLat", swLat)
                .addValue("swLng", swLng)
                .addValue("neLat", neLat)
                .addValue("neLng", neLng);

        List<CctvPoint> cctv = layers.contains("cctv") ? queryCctv(params) : null;
        List<SecurityLightPoint> securityLight = layers.contains("securityLight") ? querySecurityLight(params) : null;
        List<SafetyBellPoint> safetyBell = layers.contains("safetyBell") ? querySafetyBell(params) : null;
        List<CrimeZonePoint> crimeZone = layers.contains("crimeZone") ? queryCrimeZone(params) : null;

        return new SafetyLayerResponse(cctv, securityLight, safetyBell, crimeZone);
    }

    private List<CctvPoint> queryCctv(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, address, camera_count "
                + "FROM cctv "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new CctvPoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("address"),
                rs.getInt("camera_count")
        ));
    }

    private List<SecurityLightPoint> querySecurityLight(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, address "
                + "FROM security_light "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new SecurityLightPoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("address")
        ));
    }

    private List<SafetyBellPoint> querySafetyBell(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, address "
                + "FROM safety_bell "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new SafetyBellPoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("address")
        ));
    }

    private List<CrimeZonePoint> queryCrimeZone(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, grade "
                + "FROM crime_zone "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new CrimeZonePoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getInt("grade")
        ));
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyLayerQueryServiceIntegrationTest"`
Expected: PASS (실제 Supabase DB에 연결되어 두 테스트 모두 통과)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/safewalk/safety/SafetyLayerQueryService.java src/test/java/com/safewalk/safety/SafetyLayerQueryServiceIntegrationTest.java
git commit -m "feat: add SafetyLayerQueryService with bounds queries"
```

---

### Task 3: SafetyLayerController (HTTP 엔드포인트 + 검증)

**Files:**
- Create: `src/main/java/com/safewalk/safety/SafetyLayerController.java`
- Test: `src/test/java/com/safewalk/safety/SafetyLayerControllerTest.java`

**Interfaces:**
- Consumes: `SafetyLayerQueryService.getLayers(double, double, double, double, Set<String>): SafetyLayerResponse` (Task 2)
- Produces: `GET /api/safety/layers?swLat={}&swLng={}&neLat={}&neLng={}&layers={csv}` HTTP 엔드포인트

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/safewalk/safety/SafetyLayerControllerTest.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.CctvPoint;
import com.safewalk.safety.dto.CrimeZonePoint;
import com.safewalk.safety.dto.SafetyLayerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SafetyLayerController.class)
class SafetyLayerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SafetyLayerQueryService safetyLayerQueryService;

    @Test
    void missingBoundsParamReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLng", "126.9").param("neLat", "37.6").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "-91").param("swLng", "126.9")
                        .param("neLat", "37.6").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invertedBoundsReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.6").param("swLng", "126.9")
                        .param("neLat", "37.5").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boundsExceedingMaxSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.0").param("swLng", "126.0")
                        .param("neLat", "38.0").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownLayerNameReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.55").param("swLng", "126.97")
                        .param("neLat", "37.56").param("neLng", "126.98")
                        .param("layers", "notARealLayer"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingLayersParamReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.55").param("swLng", "126.97")
                        .param("neLat", "37.56").param("neLng", "126.98"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestReturnsOnlyRequestedLayers() throws Exception {
        SafetyLayerResponse mockResponse = new SafetyLayerResponse(
                List.of(new CctvPoint(1, 37.5665, 126.9780, "서울 중구", 2)),
                null,
                null,
                List.of(new CrimeZonePoint(3, 37.555, 126.975, 7))
        );
        when(safetyLayerQueryService.getLayers(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anySet()))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.55").param("swLng", "126.97")
                        .param("neLat", "37.56").param("neLng", "126.98")
                        .param("layers", "cctv,crimeZone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cctv[0].cameraCount").value(2))
                .andExpect(jsonPath("$.crimeZone[0].grade").value(7))
                .andExpect(jsonPath("$.securityLight").doesNotExist())
                .andExpect(jsonPath("$.safetyBell").doesNotExist());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyLayerControllerTest"`
Expected: FAIL (컴파일 에러 — `SafetyLayerController` 클래스가 없음)

- [ ] **Step 3: 컨트롤러 구현**

`src/main/java/com/safewalk/safety/SafetyLayerController.java`:

```java
package com.safewalk.safety;

import com.safewalk.safety.dto.SafetyLayerResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.Set;

@RestController
public class SafetyLayerController {

    private static final Set<String> ALLOWED_LAYERS = Set.of("cctv", "securityLight", "safetyBell", "crimeZone");
    private static final double MAX_BOUNDS_METERS = 5000.0;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private final SafetyLayerQueryService safetyLayerQueryService;

    public SafetyLayerController(SafetyLayerQueryService safetyLayerQueryService) {
        this.safetyLayerQueryService = safetyLayerQueryService;
    }

    @GetMapping("/api/safety/layers")
    public SafetyLayerResponse getLayers(
            @RequestParam double swLat,
            @RequestParam double swLng,
            @RequestParam double neLat,
            @RequestParam double neLng,
            @RequestParam String layers) {
        validateCoordinate(swLat, -90, 90, "swLat");
        validateCoordinate(neLat, -90, 90, "neLat");
        validateCoordinate(swLng, -180, 180, "swLng");
        validateCoordinate(neLng, -180, 180, "neLng");

        if (swLat >= neLat || swLng >= neLng) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "swLat/swLng must be less than neLat/neLng");
        }

        if (exceedsMaxBoundsSize(swLat, swLng, neLat, neLng)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bounds must not exceed 5000m on either side");
        }

        Set<String> layerSet = parseLayers(layers);

        return safetyLayerQueryService.getLayers(swLat, swLng, neLat, neLng, layerSet);
    }

    private void validateCoordinate(double value, double min, double max, String name) {
        if (value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be between " + min + " and " + max);
        }
    }

    private boolean exceedsMaxBoundsSize(double swLat, double swLng, double neLat, double neLng) {
        double latMeters = Math.abs(neLat - swLat) * METERS_PER_DEGREE_LAT;
        double avgLatRad = Math.toRadians((swLat + neLat) / 2.0);
        double lngMeters = Math.abs(neLng - swLng) * METERS_PER_DEGREE_LAT * Math.cos(avgLatRad);
        return latMeters > MAX_BOUNDS_METERS || lngMeters > MAX_BOUNDS_METERS;
    }

    private Set<String> parseLayers(String layers) {
        Set<String> result = new LinkedHashSet<>();
        for (String layer : layers.split(",")) {
            String trimmed = layer.trim();
            if (trimmed.isEmpty() || !ALLOWED_LAYERS.contains(trimmed)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layers must be a comma-separated list of: " + ALLOWED_LAYERS);
            }
            result.add(trimmed);
        }
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layers must not be empty");
        }
        return result;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.safewalk.safety.SafetyLayerControllerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/safewalk/safety/SafetyLayerController.java src/test/java/com/safewalk/safety/SafetyLayerControllerTest.java
git commit -m "feat: add GET /api/safety/layers endpoint"
```

---

### Task 4: 수동 End-to-End 검증

**Files:** 없음 (코드 변경 없음, 실행 중인 앱에 대한 수동 확인)

**Interfaces:**
- Consumes: `GET /api/safety/layers?swLat={}&swLng={}&neLat={}&neLng={}&layers={csv}` (Task 3)

- [ ] **Step 1: 애플리케이션 기동**

Run: `./gradlew bootRun` (백그라운드 실행)
Expected: 로그에 `Started SafewalkApplication` 출력

- [ ] **Step 2: 실제 CCTV 좌표 주변 bounds로 단일 레이어 조회**

Run:
```bash
curl -s "http://localhost:8080/api/safety/summary?lat=37.5665&lng=126.9780"
```
로 근처에 데이터 있는 좌표를 먼저 확인한 뒤, 그 좌표를 중심으로 작은 bounds를 잡아 다음을 실행:
```bash
curl -s "http://localhost:8080/api/safety/layers?swLat=37.560&swLng=126.973&neLat=37.573&neLng=126.983&layers=cctv"
```
Expected: HTTP 200, `{"cctv":[...]}` 형태이고 `securityLight`/`safetyBell`/`crimeZone` 키는 없음

- [ ] **Step 3: 복수 레이어 조회**

Run: `curl -s "http://localhost:8080/api/safety/layers?swLat=37.560&swLng=126.973&neLat=37.573&neLng=126.983&layers=cctv,safetyBell"`
Expected: HTTP 200, `cctv`와 `safetyBell` 키만 존재

- [ ] **Step 4: bounds 크기 초과로 400 확인**

Run: `curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/safety/layers?swLat=37.0&swLng=126.0&neLat=38.0&neLng=127.0&layers=cctv"`
Expected: `400`

- [ ] **Step 5: layers 파라미터 누락으로 400 확인**

Run: `curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/safety/layers?swLat=37.560&swLng=126.973&neLat=37.573&neLng=126.983"`
Expected: `400`

- [ ] **Step 6: 잘못된 박스(역전)로 400 확인**

Run: `curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/api/safety/layers?swLat=37.573&swLng=126.973&neLat=37.560&neLng=126.983&layers=cctv"`
Expected: `400`

- [ ] **Step 7: 애플리케이션 종료**

`bootRun` 프로세스 종료 (`netstat -ano`로 8080 포트 PID 확인 후 `taskkill //F //PID <pid>`)
