package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;
import com.safewalk.route.dto.RefugePoint;
import com.safewalk.route.dto.RouteResponse;
import com.safewalk.route.dto.RouteShelter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 로컬 Docker DB(db/climate/load.sh로 적재)에서만 도는 통합 테스트.
 * 실행: SPRING_PROFILES_ACTIVE=local ./gradlew test --tests '*RouteServiceClimateIntegrationTest'
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = ".*local.*")
class RouteServiceClimateIntegrationTest {

    // 시연 구간: 한성대입구역 → 한성대학교
    private static final double START_LAT = 37.5884;
    private static final double START_LNG = 127.0062;
    private static final double END_LAT = 37.5826;
    private static final double END_LNG = 127.0103;

    @Autowired
    private RouteService routeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"SHORTEST", "SAFE", "HEAT", "RAIN"})
    void coordinatesRunFromStartNodeToEndNode(String mode) {
        RouteResponse route = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, mode);
        List<Coordinate> coordinates = route.coordinates();

        assertThat(coordinates).hasSizeGreaterThanOrEqualTo(2);
        assertThat(coordinates.get(0)).isEqualTo(nearestVertex(START_LAT, START_LNG));
        assertThat(coordinates.get(coordinates.size() - 1)).isEqualTo(nearestVertex(END_LAT, END_LNG));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORTEST", "SAFE", "HEAT", "RAIN"})
    void polylineFollowsEdgeShapesWithoutJumps(String mode) {
        RouteResponse route = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, mode);
        List<Coordinate> coordinates = route.coordinates();

        for (int i = 1; i < coordinates.size(); i++) {
            assertThat(coordinates.get(i)).isNotEqualTo(coordinates.get(i - 1));
        }
        // 간선 방향이 하나라도 뒤집히면 선이 앞뒤로 튀어 폴리라인 길이가 간선 길이 합보다 크게 늘어난다.
        double polylineMeters = polylineLengthMeters(coordinates);
        assertThat(polylineMeters).isBetween(
                route.totalDistanceMeters() * 0.98 - 1.0,
                route.totalDistanceMeters() * 1.02 + 1.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORTEST", "SAFE", "HEAT", "RAIN"})
    void exposureIsFilledForEveryMode(String mode) {
        RouteResponse route = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, mode);

        assertThat(route.exposure()).isNotNull();
        assertThat(route.exposure().shadeRatio()).isBetween(0.0, 1.0);
        assertThat(route.exposure().floodZoneMeters()).isBetween(0.0, route.totalDistanceMeters() + 1e-6);
        assertThat(route.exposure().underpassMeters()).isBetween(0.0, route.totalDistanceMeters() + 1e-6);
        assertThat(route.estimatedSafetyScore()).isEqualTo(70);
    }

    @Test
    void heatModeListsSheltersWithin150mNearestFirst() {
        RouteResponse route = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, "HEAT");
        List<RouteShelter> shelters = route.shelters();

        assertThat(shelters).hasSizeLessThanOrEqualTo(20);
        assertThat(shelters).allSatisfy(s -> assertThat(s.distanceFromRouteM()).isBetween(0.0, 150.0));
        for (int i = 1; i < shelters.size(); i++) {
            assertThat(shelters.get(i).distanceFromRouteM())
                    .isGreaterThanOrEqualTo(shelters.get(i - 1).distanceFromRouteM());
        }
        assertThat(route.refuge()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORTEST", "SAFE", "RAIN"})
    void nonHeatModesHaveNoShelters(String mode) {
        RouteResponse route = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, mode);

        assertThat(route.shelters()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHORTEST", "SAFE", "HEAT"})
    void nonRainModesHaveNoRefuge(String mode) {
        RouteResponse route = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, mode);

        assertThat(route.refuge()).isNull();
    }

    @Test
    void rainModeRefugeIsOutsideFloodZonesAndHigherThanSurroundings() {
        // 침수 흔적 폴리곤 안 꼭짓점을 출발지로, 간선 하나로 이어진 이웃 꼭짓점을 도착지로 쓴다(경로가 반드시 존재)
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT s.lat AS start_lat, s.lon AS start_lng, t.lat AS end_lat, t.lon AS end_lng
                FROM ways w
                JOIN ways_vertices_pgr s ON s.id = w.source
                JOIN ways_vertices_pgr t ON t.id = w.target
                WHERE w.source <> w.target
                  AND EXISTS (SELECT 1 FROM flood_trace f WHERE ST_Intersects(f.geom, s.the_geom))
                ORDER BY w.gid
                LIMIT 10
                """);
        assumeThat(rows).as("침수 흔적 구역 안 꼭짓점이 있어야 검증할 수 있다").isNotEmpty();

        int refugesFound = 0;
        for (Map<String, Object> row : rows) {
            double lat = ((Number) row.get("start_lat")).doubleValue();
            double lng = ((Number) row.get("start_lng")).doubleValue();
            RefugePoint refuge = routeService.findRoute(lat, lng,
                    ((Number) row.get("end_lat")).doubleValue(), ((Number) row.get("end_lng")).doubleValue(),
                    "RAIN").refuge();
            if (refuge == null) {
                continue; // 조건을 만족하는 꼭짓점이 없으면 null이 정상
            }
            refugesFound++;

            Boolean refugeInFloodZone = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM flood_trace WHERE ST_Intersects(geom, ST_SetSRID(ST_MakePoint(?, ?), 4326)))",
                    Boolean.class, refuge.lng(), refuge.lat());
            Double baseElevation = jdbcTemplate.queryForObject("""
                    SELECT AVG(elevation_m) FROM ways_vertices_pgr
                    WHERE ST_DWithin(the_geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 100)
                    """, Double.class, lng, lat);

            assertThat(refugeInFloodZone).isFalse();
            assertThat(refuge.distanceM()).isBetween(0.0, 1000.0);
            assertThat(baseElevation).isNotNull();
            assertThat(refuge.elevationM()).isGreaterThanOrEqualTo(baseElevation + 3.0);

            // 대피 지점까지 실제로 걸어갈 수 있어야 한다
            RouteResponse toRefuge = routeService.findRoute(lat, lng, refuge.lat(), refuge.lng(), "SHORTEST");
            assertThat(toRefuge.coordinates()).isNotEmpty();
        }
        assumeThat(refugesFound).as("후보 출발지 모두 조건을 만족하는 대피 지점이 없음").isPositive();
    }

    private Coordinate nearestVertex(double lat, double lng) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT ST_Y(the_geom) AS lat, ST_X(the_geom) AS lng FROM ways_vertices_pgr "
                        + "ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(?, ?), 4326) LIMIT 1",
                lng, lat);
        return new Coordinate(((Number) row.get("lat")).doubleValue(), ((Number) row.get("lng")).doubleValue());
    }

    private static double polylineLengthMeters(List<Coordinate> coordinates) {
        double total = 0.0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += haversineMeters(coordinates.get(i - 1), coordinates.get(i));
        }
        return total;
    }

    private static double haversineMeters(Coordinate a, Coordinate b) {
        double earthRadiusM = 6_371_008.8;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * earthRadiusM * Math.asin(Math.sqrt(h));
    }
}
