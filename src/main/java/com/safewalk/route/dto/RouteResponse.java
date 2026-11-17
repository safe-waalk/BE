package com.safewalk.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * @param shelters HEAT 모드에서만 채운다. 다른 모드는 빈 리스트.
 * @param refuge   RAIN 모드에서 출발지/도착지가 침수 흔적 구역 안일 때만 채운다. 그 외 null(JSON 생략).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouteResponse(
        List<Coordinate> coordinates,
        double totalDistanceMeters,
        int estimatedSafetyScore,
        RouteExposure exposure,
        List<RouteShelter> shelters,
        RefugePoint refuge) {

    /** 기후 필드 없이 만드는 생성자. 기존 호출부 호환용. */
    public RouteResponse(List<Coordinate> coordinates, double totalDistanceMeters, int estimatedSafetyScore) {
        this(coordinates, totalDistanceMeters, estimatedSafetyScore, null, List.of(), null);
    }
}
