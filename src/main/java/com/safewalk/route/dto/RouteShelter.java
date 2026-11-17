package com.safewalk.route.dto;

/**
 * 경로 주변 무더위쉼터. distanceFromRouteM은 경로 선형과의 최단거리(미터).
 */
public record RouteShelter(long id, String name, double lat, double lng, double distanceFromRouteM) {
}
