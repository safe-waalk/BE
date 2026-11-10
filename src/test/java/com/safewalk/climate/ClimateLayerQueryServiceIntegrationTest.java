package com.safewalk.climate;

import com.safewalk.climate.dto.FloodZone;
import com.safewalk.climate.dto.ShelterListResponse;
import com.safewalk.route.dto.Coordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 Docker DB(db/climate/load.sh로 적재)에서만 도는 통합 테스트.
 * 실행: SPRING_PROFILES_ACTIVE=local ./gradlew test --tests '*ClimateLayerQueryServiceIntegrationTest'
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = ".*local.*")
class ClimateLayerQueryServiceIntegrationTest {

    // 시연 구간을 감싸는 bbox
    private static final double SW_LAT = 37.578;
    private static final double SW_LNG = 127.000;
    private static final double NE_LAT = 37.592;
    private static final double NE_LNG = 127.014;
    private static final double EPSILON = 1e-9;

    @Autowired
    private ClimateLayerQueryService climateLayerQueryService;

    @Test
    void sheltersAreInsideRequestedBounds() {
        ShelterListResponse response = climateLayerQueryService.getShelters(SW_LAT, SW_LNG, NE_LAT, NE_LNG);

        assertThat(response.shelters()).isNotEmpty();
        assertThat(response.shelters()).allSatisfy(s -> {
            assertThat(s.lat()).isBetween(SW_LAT, NE_LAT);
            assertThat(s.lng()).isBetween(SW_LNG, NE_LNG);
            assertThat(s.name()).isNotBlank();
        });
    }

    @Test
    void floodZonesAreClippedClosedRings() {
        List<FloodZone> zones = climateLayerQueryService.getFloodZones(SW_LAT, SW_LNG, NE_LAT, NE_LNG).floodZones();

        assertThat(zones).allSatisfy(zone -> {
            assertThat(zone.year()).isNotNull();
            assertThat(zone.polygons()).isNotEmpty();
            for (List<List<Coordinate>> polygon : zone.polygons()) {
                assertThat(polygon).isNotEmpty();
                for (List<Coordinate> ring : polygon) {
                    assertThat(ring).hasSizeGreaterThanOrEqualTo(4);
                    assertThat(ring.get(0)).isEqualTo(ring.get(ring.size() - 1));
                    assertThat(ring).allSatisfy(p -> {
                        assertThat(p.lat()).isBetween(SW_LAT - EPSILON, NE_LAT + EPSILON);
                        assertThat(p.lng()).isBetween(SW_LNG - EPSILON, NE_LNG + EPSILON);
                    });
                }
            }
        });
    }
}
