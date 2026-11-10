package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;
import com.safewalk.route.dto.RouteExposure;

import java.util.ArrayList;
import java.util.List;

/**
 * 경로 간선 목록을 응답 값으로 바꾸는 순수 계산. DB에 의존하지 않는다.
 */
final class RouteAssembler {

    static final int DEFAULT_SAFETY_SCORE = 70;

    // 같은 꼭짓점에서 나온 좌표는 값이 같지만, 부동소수 표현 차이를 흡수하려고 약 1cm 허용
    private static final double SAME_POINT_EPSILON_DEG = 1e-7;

    private RouteAssembler() {
    }

    /** 간선 형상점을 이어 붙인다. 앞 간선의 마지막 점과 같은 점은 한 번만 넣는다. */
    static List<Coordinate> mergeCoordinates(List<RouteEdge> edges) {
        List<Coordinate> merged = new ArrayList<>();
        for (RouteEdge edge : edges) {
            for (Coordinate point : edge.points()) {
                if (!merged.isEmpty() && isSamePoint(merged.get(merged.size() - 1), point)) {
                    continue;
                }
                merged.add(point);
            }
        }
        return merged;
    }

    static double totalDistanceMeters(List<RouteEdge> edges) {
        return edges.stream().mapToDouble(RouteEdge::lengthM).sum();
    }

    /** safety_score가 있는 간선의 평균. 하나도 없으면 기본값 70. */
    static int estimatedSafetyScore(List<RouteEdge> edges) {
        double total = 0.0;
        int count = 0;
        for (RouteEdge edge : edges) {
            if (edge.safetyScore() != null) {
                total += edge.safetyScore();
                count++;
            }
        }
        return count > 0 ? (int) Math.round(total / count) : DEFAULT_SAFETY_SCORE;
    }

    /**
     * shadeRatio = Σ(length_m × shade_ratio) / Σ(length_m),
     * floodZoneMeters / underpassMeters = 해당 간선 length_m 합.
     */
    static RouteExposure exposure(List<RouteEdge> edges) {
        double totalLength = 0.0;
        double shadedLength = 0.0;
        double floodZoneMeters = 0.0;
        double underpassMeters = 0.0;
        for (RouteEdge edge : edges) {
            totalLength += edge.lengthM();
            shadedLength += edge.lengthM() * edge.shadeRatio();
            if (edge.inFloodZone()) {
                floodZoneMeters += edge.lengthM();
            }
            if (edge.underpass()) {
                underpassMeters += edge.lengthM();
            }
        }
        double shadeRatio = totalLength > 0 ? shadedLength / totalLength : 0.0;
        return new RouteExposure(shadeRatio, floodZoneMeters, underpassMeters);
    }

    private static boolean isSamePoint(Coordinate a, Coordinate b) {
        return Math.abs(a.lat() - b.lat()) < SAME_POINT_EPSILON_DEG
                && Math.abs(a.lng() - b.lng()) < SAME_POINT_EPSILON_DEG;
    }
}
