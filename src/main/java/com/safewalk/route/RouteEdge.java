package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;

import java.util.List;

/**
 * 경로가 지나는 간선 하나. points는 진행 방향(출발→도착)으로 정렬된 형상점이다.
 *
 * @param underpass ways.underpass가 'NONE'이 아니면 true (지하보도·지하차도)
 */
record RouteEdge(
        long edgeId,
        double lengthM,
        double shadeRatio,
        boolean inFloodZone,
        boolean underpass,
        Double safetyScore,
        List<Coordinate> points) {
}
