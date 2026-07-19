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
