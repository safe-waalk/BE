package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;
import com.safewalk.route.dto.RouteExposure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteAssemblerTest {

    private static final Coordinate A = new Coordinate(37.5884, 127.0062);
    private static final Coordinate B = new Coordinate(37.5880, 127.0065);
    private static final Coordinate C = new Coordinate(37.5876, 127.0070);
    private static final Coordinate D = new Coordinate(37.5870, 127.0074);
    private static final Coordinate E = new Coordinate(37.5865, 127.0080);

    private static RouteEdge edge(double lengthM, double shadeRatio, boolean inFloodZone, boolean underpass,
                                  Double safetyScore, Coordinate... points) {
        return new RouteEdge(1L, lengthM, shadeRatio, inFloodZone, underpass, safetyScore, List.of(points));
    }

    @Test
    void mergeCoordinatesKeepsShapePointsAndDropsSeamDuplicates() {
        List<RouteEdge> edges = List.of(
                edge(100, 0, false, false, null, A, B, C),
                edge(100, 0, false, false, null, C, D),
                edge(100, 0, false, false, null, D, E));

        assertThat(RouteAssembler.mergeCoordinates(edges)).containsExactly(A, B, C, D, E);
    }

    @Test
    void mergeCoordinatesTreatsNearlyEqualSeamPointsAsSame() {
        Coordinate cAlmost = new Coordinate(C.lat() + 1e-9, C.lng() - 1e-9);
        List<RouteEdge> edges = List.of(
                edge(100, 0, false, false, null, A, C),
                edge(100, 0, false, false, null, cAlmost, E));

        assertThat(RouteAssembler.mergeCoordinates(edges)).containsExactly(A, C, E);
    }

    @Test
    void mergeCoordinatesKeepsDistinctConsecutivePoints() {
        List<RouteEdge> edges = List.of(
                edge(100, 0, false, false, null, A, B),
                edge(100, 0, false, false, null, C, D));

        assertThat(RouteAssembler.mergeCoordinates(edges)).containsExactly(A, B, C, D);
    }

    @Test
    void exposureIsLengthWeighted() {
        List<RouteEdge> edges = List.of(
                edge(100, 1.0, false, false, null, A, B),
                edge(300, 0.0, true, false, null, B, C),
                edge(50, 0.4, true, true, null, C, D),
                edge(50, 0.0, false, true, null, D, E));

        RouteExposure exposure = RouteAssembler.exposure(edges);

        // (100*1.0 + 300*0 + 50*0.4 + 50*0) / 500 = 120 / 500
        assertThat(exposure.shadeRatio()).isCloseTo(0.24, within(1e-9));
        assertThat(exposure.floodZoneMeters()).isCloseTo(350.0, within(1e-9));
        assertThat(exposure.underpassMeters()).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void exposureOfZeroLengthRouteIsZero() {
        RouteExposure exposure = RouteAssembler.exposure(List.of(edge(0, 1.0, true, true, null, A, B)));

        assertThat(exposure.shadeRatio()).isZero();
        assertThat(exposure.floodZoneMeters()).isZero();
        assertThat(exposure.underpassMeters()).isZero();
    }

    @Test
    void totalDistanceIsSumOfEdgeLengths() {
        List<RouteEdge> edges = List.of(
                edge(120.5, 0, false, false, null, A, B),
                edge(79.5, 0, false, false, null, B, C));

        assertThat(RouteAssembler.totalDistanceMeters(edges)).isCloseTo(200.0, within(1e-9));
    }

    @Test
    void safetyScoreFallsBackTo70WhenAllNull() {
        List<RouteEdge> edges = List.of(
                edge(100, 0, false, false, null, A, B),
                edge(100, 0, false, false, null, B, C));

        assertThat(RouteAssembler.estimatedSafetyScore(edges)).isEqualTo(70);
    }

    @Test
    void safetyScoreAveragesNonNullValues() {
        List<RouteEdge> edges = List.of(
                edge(100, 0, false, false, 80.0, A, B),
                edge(100, 0, false, false, null, B, C),
                edge(100, 0, false, false, 61.0, C, D));

        assertThat(RouteAssembler.estimatedSafetyScore(edges)).isEqualTo(71);
    }
}
