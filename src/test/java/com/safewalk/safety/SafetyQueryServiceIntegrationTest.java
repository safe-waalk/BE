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
