package com.safewalk.climate;

import com.safewalk.climate.dto.FloodZone;
import com.safewalk.route.dto.Coordinate;

import java.util.ArrayList;
import java.util.List;

/**
 * ST_DumpPoints 결과 행(id, 폴리곤 번호, 링 번호, 점)을 FloodZone의 중첩 배열로 묶는다.
 * 행은 id, 폴리곤 번호, 링 번호, 점 번호 순으로 정렬되어 들어와야 한다.
 */
final class FloodZoneAssembler {

    private final List<FloodZone> zones = new ArrayList<>();

    private long currentId;
    private int currentPolygon;
    private int currentRing;
    private List<List<List<Coordinate>>> polygons;
    private List<List<Coordinate>> rings;
    private List<Coordinate> ring;

    void addPoint(long id, Integer year, int polygonIndex, int ringIndex, double lat, double lng) {
        if (polygons == null || id != currentId) {
            polygons = new ArrayList<>();
            zones.add(new FloodZone(id, year, polygons));
            currentId = id;
            currentPolygon = Integer.MIN_VALUE;
        }
        if (polygonIndex != currentPolygon) {
            rings = new ArrayList<>();
            polygons.add(rings);
            currentPolygon = polygonIndex;
            currentRing = Integer.MIN_VALUE;
        }
        if (ringIndex != currentRing) {
            ring = new ArrayList<>();
            rings.add(ring);
            currentRing = ringIndex;
        }
        ring.add(new Coordinate(lat, lng));
    }

    List<FloodZone> zones() {
        return zones;
    }
}
