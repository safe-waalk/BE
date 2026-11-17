package com.safewalk.route;

import com.safewalk.route.dto.RouteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RouteServiceIntegrationTest {

    @Autowired
    private RouteService routeService;

    // 서울시청 근처 두 좌표 (verify-walk-network.sql과 동일)
    private static final double START_LAT = 37.5665;
    private static final double START_LNG = 126.9780;
    private static final double END_LAT   = 37.5600;
    private static final double END_LNG   = 126.9700;

    @Test
    void safeRouteScoreIsGreaterThanOrEqualToShortestRouteScore() {
        RouteResponse safe     = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, "SAFE");
        RouteResponse shortest = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, "SHORTEST");

        assertThat(safe.estimatedSafetyScore())
                .isGreaterThanOrEqualTo(shortest.estimatedSafetyScore());
    }

    @Test
    void shortestRouteDistanceIsLessThanOrEqualToSafeRouteDistance() {
        RouteResponse safe     = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, "SAFE");
        RouteResponse shortest = routeService.findRoute(START_LAT, START_LNG, END_LAT, END_LNG, "SHORTEST");

        assertThat(shortest.totalDistanceMeters())
                .isLessThanOrEqualTo(safe.totalDistanceMeters());
    }

    @Test
    void coordinatesOutsideSeoulReturns404() {
        // 제주도 좌표 — 서울 도보망에서 1km 초과 거리
        assertThatThrownBy(() ->
                routeService.findRoute(33.4996, 126.5312, 33.4900, 126.5200, "SAFE"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
