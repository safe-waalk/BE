package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;
import com.safewalk.route.dto.RefugePoint;
import com.safewalk.route.dto.RouteResponse;
import com.safewalk.route.dto.RouteShelter;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RouteService {

    private static final double MAX_SNAP_DISTANCE_M = 1000.0;

    private static final double SHELTER_RADIUS_M = 150.0;
    private static final int SHELTER_LIMIT = 20;

    private static final double REFUGE_SEARCH_RADIUS_M = 1000.0;
    private static final double REFUGE_BASE_RADIUS_M = 100.0;
    private static final double REFUGE_MIN_RISE_M = 3.0;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public RouteService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RouteResponse findRoute(
            double startLat, double startLng,
            double endLat, double endLng,
            String mode) {

        long sourceId = findNearestNode(startLat, startLng);
        long targetId = findNearestNode(endLat, endLng);

        // mode는 컨트롤러에서 이미 RouteMode 값으로 검증됨
        RouteMode routeMode = RouteMode.valueOf(mode);

        List<RouteEdge> edges = findRouteEdges(routeMode, sourceId, targetId);
        if (edges.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "두 지점 사이 경로를 찾을 수 없습니다");
        }

        List<RouteShelter> shelters = routeMode == RouteMode.HEAT
                ? findSheltersAlongRoute(edges.stream().map(RouteEdge::edgeId).toList())
                : List.of();
        RefugePoint refuge = routeMode == RouteMode.RAIN
                ? findRefuge(startLat, startLng, sourceId, endLat, endLng, targetId)
                : null;

        return new RouteResponse(
                RouteAssembler.mergeCoordinates(edges),
                RouteAssembler.totalDistanceMeters(edges),
                RouteAssembler.estimatedSafetyScore(edges),
                RouteAssembler.exposure(edges),
                shelters,
                refuge);
    }

    /**
     * pgr_dijkstra 결과를 간선 단위로 묶어 돌려준다. 각 간선의 형상점은 진행 방향으로 정렬된다.
     * pgr_dijkstra의 각 행은 (node, 그 node에서 다음 node로 가는 edge)이고 마지막 행의 edge는 -1이다.
     * directed := false라 간선을 target→source로 지날 수 있으므로, node가 source가 아니면 ST_Reverse로 뒤집는다.
     */
    private List<RouteEdge> findRouteEdges(RouteMode routeMode, long sourceId, long targetId) {
        String sql = """
                WITH route AS (
                    SELECT seq, node, edge
                    FROM pgr_dijkstra('%s', :source, :target, directed := false)
                ),
                route_edges AS (
                    SELECT
                        r.seq,
                        w.gid,
                        COALESCE(w.length_m, ST_Length(w.the_geom::geography)) AS length_m,
                        COALESCE(w.shade_ratio, 0) AS shade_ratio,
                        COALESCE(w.in_flood_zone, false) AS in_flood_zone,
                        COALESCE(w.underpass, 'NONE') <> 'NONE' AS underpass,
                        w.safety_score,
                        CASE WHEN r.node = w.source THEN w.the_geom ELSE ST_Reverse(w.the_geom) END AS geom
                    FROM route r
                    JOIN ways w ON w.gid = r.edge
                )
                SELECT
                    e.seq, e.gid, e.length_m, e.shade_ratio, e.in_flood_zone, e.underpass, e.safety_score,
                    ST_Y(p.geom) AS lat,
                    ST_X(p.geom) AS lng
                FROM route_edges e
                CROSS JOIN LATERAL ST_DumpPoints(e.geom) AS p
                ORDER BY e.seq, p.path[1]
                """.formatted(routeMode.edgesSql());

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("source", sourceId)
                .addValue("target", targetId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);

        List<RouteEdge> edges = new ArrayList<>();
        long currentSeq = Long.MIN_VALUE;
        List<Coordinate> points = null;
        for (Map<String, Object> row : rows) {
            long seq = ((Number) row.get("seq")).longValue();
            if (seq != currentSeq) {
                points = new ArrayList<>();
                edges.add(new RouteEdge(
                        ((Number) row.get("gid")).longValue(),
                        ((Number) row.get("length_m")).doubleValue(),
                        ((Number) row.get("shade_ratio")).doubleValue(),
                        (Boolean) row.get("in_flood_zone"),
                        (Boolean) row.get("underpass"),
                        row.get("safety_score") == null ? null : ((Number) row.get("safety_score")).doubleValue(),
                        points));
                currentSeq = seq;
            }
            double lat = ((Number) row.get("lat")).doubleValue();
            double lng = ((Number) row.get("lng")).doubleValue();
            points.add(new Coordinate(lat, lng));
        }
        return edges;
    }

    /** 경로 선형에서 SHELTER_RADIUS_M 이내 무더위쉼터를 가까운 순으로. */
    private List<RouteShelter> findSheltersAlongRoute(List<Long> edgeIds) {
        String sql = """
                WITH route AS (
                    SELECT ST_Collect(the_geom)::geography AS geog
                    FROM ways
                    WHERE gid IN (:edgeIds)
                )
                SELECT
                    s.id, s.name,
                    ST_Y(s.geom) AS lat,
                    ST_X(s.geom) AS lng,
                    ST_Distance(s.geom::geography, route.geog) AS distance_m
                FROM cooling_shelter s, route
                WHERE ST_DWithin(s.geom::geography, route.geog, :radius)
                ORDER BY distance_m
                LIMIT %d
                """.formatted(SHELTER_LIMIT);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("edgeIds", edgeIds)
                .addValue("radius", SHELTER_RADIUS_M);

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new RouteShelter(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getDouble("distance_m")
        ));
    }

    /**
     * 출발지가 침수 흔적 구역 안이면 출발지 기준, 아니고 도착지가 구역 안이면 도착지 기준으로 대피 지점을 찾는다.
     * 둘 다 구역 밖이면 null.
     */
    private RefugePoint findRefuge(double startLat, double startLng, long sourceId,
                                   double endLat, double endLng, long targetId) {
        if (isInFloodZone(startLat, startLng)) {
            return findRefugeNear(startLat, startLng, sourceId);
        }
        if (isInFloodZone(endLat, endLng)) {
            return findRefugeNear(endLat, endLng, targetId);
        }
        return null;
    }

    private boolean isInFloodZone(double lat, double lng) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM flood_trace
                    WHERE ST_Intersects(geom, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
                )
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng);

        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, params, Boolean.class));
    }

    /**
     * 기준 지점에서 REFUGE_SEARCH_RADIUS_M 안의 꼭짓점 중
     * (a) 어떤 침수 흔적 폴리곤에도 들지 않고
     * (b) 기준 지점 주변 고도(반경 REFUGE_BASE_RADIUS_M 안 꼭짓점 고도 평균)보다 REFUGE_MIN_RISE_M 이상 높은
     * 가장 가까운 꼭짓점. 없으면 null — 조건에 맞지 않는 점을 대신 내놓지 않는다.
     * (c) 기준 지점이 스냅된 꼭짓점(baseNodeId)과 같은 연결 컴포넌트여야 한다 — 걸어서 갈 수 없는 점은 내놓지 않는다.
     */
    private RefugePoint findRefugeNear(double lat, double lng, long baseNodeId) {
        String sql = """
                WITH cc AS (
                    SELECT node, component
                    FROM pgr_connectedComponents('SELECT gid AS id, source, target, cost, reverse_cost FROM ways')
                ),
                reachable AS (
                    SELECT node FROM cc
                    WHERE component = (SELECT component FROM cc WHERE node = :baseNode)
                ),
                p AS (
                    SELECT ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography AS geog
                ),
                base AS (
                    SELECT AVG(v.elevation_m) AS elevation_m
                    FROM ways_vertices_pgr v, p
                    WHERE ST_DWithin(v.the_geom::geography, p.geog, :baseRadius)
                )
                SELECT
                    v.lat, v.lon, v.elevation_m,
                    ST_Distance(v.the_geom::geography, p.geog) AS distance_m
                FROM ways_vertices_pgr v, p, base
                WHERE ST_DWithin(v.the_geom::geography, p.geog, :searchRadius)
                  AND v.elevation_m >= base.elevation_m + :minRise
                  AND v.id IN (SELECT node FROM reachable)
                  AND NOT EXISTS (
                      SELECT 1 FROM flood_trace f WHERE ST_Intersects(f.geom, v.the_geom)
                  )
                ORDER BY distance_m
                LIMIT 1
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("baseRadius", REFUGE_BASE_RADIUS_M)
                .addValue("searchRadius", REFUGE_SEARCH_RADIUS_M)
                .addValue("minRise", REFUGE_MIN_RISE_M)
                .addValue("baseNode", baseNodeId);

        List<RefugePoint> refuges = jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new RefugePoint(
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                rs.getDouble("elevation_m"),
                rs.getDouble("distance_m")
        ));
        return refuges.isEmpty() ? null : refuges.get(0);
    }

    private long findNearestNode(double lat, double lng) {
        String sql = """
                SELECT
                    id,
                    ST_Distance(the_geom::geography,
                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS dist_m
                FROM ways_vertices_pgr
                ORDER BY the_geom <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                LIMIT 1
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng);

        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(sql, params);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "경로 탐색 가능 구역이 아닙니다");
        }
        double distM = ((Number) row.get("dist_m")).doubleValue();

        if (distM > MAX_SNAP_DISTANCE_M) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "경로 탐색 가능 구역이 아닙니다");
        }

        return ((Number) row.get("id")).longValue();
    }
}
