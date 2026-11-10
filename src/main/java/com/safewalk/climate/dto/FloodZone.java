package com.safewalk.climate.dto;

import com.safewalk.route.dto.Coordinate;

import java.util.List;

/**
 * 침수 흔적 하나(flood_trace 한 행). geom이 MultiPolygon이라 polygons는 3단 중첩이다.
 * <pre>
 * polygons[폴리곤][링][점] = {lat, lng}
 *   - 링 0은 외곽선, 링 1부터는 구멍(hole)
 *   - 각 링은 닫혀 있다(첫 점 == 마지막 점)
 * </pre>
 * 좌표는 요청 bbox로 잘리고 단순화된 형상이다.
 */
public record FloodZone(long id, Integer year, List<List<List<Coordinate>>> polygons) {
}
