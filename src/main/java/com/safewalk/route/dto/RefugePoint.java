package com.safewalk.route.dto;

/**
 * 침수 구역 안의 출발지/도착지에서 가장 가까운 고지대 대피 지점.
 * distanceM은 기준 지점과의 직선거리(미터).
 */
public record RefugePoint(double lat, double lng, double elevationM, double distanceM) {
}
