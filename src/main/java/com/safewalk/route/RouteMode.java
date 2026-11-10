package com.safewalk.route;

import java.util.Arrays;

/**
 * 경로 탐색 모드와 pgr_dijkstra에 넘길 비용 식.
 * 비용 식은 이 enum 상수에서만 나오므로 SQL에 그대로 끼워 넣어도 외부 입력이 섞이지 않는다.
 */
public enum RouteMode {
    SAFE("COALESCE(safety_cost, cost)", "COALESCE(safety_cost, cost)"),
    SHORTEST("cost", "reverse_cost"),
    HEAT("heat_cost", "heat_cost"),
    RAIN("rain_cost", "rain_cost");

    private final String costExpression;
    private final String reverseCostExpression;

    RouteMode(String costExpression, String reverseCostExpression) {
        this.costExpression = costExpression;
        this.reverseCostExpression = reverseCostExpression;
    }

    public static boolean isSupported(String mode) {
        return Arrays.stream(values()).anyMatch(m -> m.name().equals(mode));
    }

    String edgesSql() {
        return "SELECT gid AS id, source, target, " + costExpression + " AS cost, "
                + reverseCostExpression + " AS reverse_cost FROM ways";
    }
}
