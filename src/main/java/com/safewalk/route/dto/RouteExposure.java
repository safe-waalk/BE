package com.safewalk.route.dto;

/**
 * 경로의 기후 노출도. 모든 모드에서 채워 최단 경로와 비교할 수 있게 한다.
 *
 * @param shadeRatio      길이 가중 평균 그늘 비율 (0.0~1.0)
 * @param floodZoneMeters 침수 흔적 구역 10m 이내를 지나는 간선 길이 합 (ways.in_flood_zone)
 * @param underpassMeters 지하보도·지하차도 간선 길이 합
 */
public record RouteExposure(double shadeRatio, double floodZoneMeters, double underpassMeters) {
}
