package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;
import com.safewalk.route.dto.RouteResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RouteService {

    private static final double MAX_SNAP_DISTANCE_M = 1000.0;

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

        // mode는 컨트롤러에서 이미 SAFE/SHORTEST로 검증됨
        String innerSql = mode.equals("SAFE")
                ? "SELECT gid AS id, source, target, COALESCE(safety_cost, cost) AS cost, COALESCE(safety_cost, cost) AS reverse_cost FROM ways"
                : "SELECT gid AS id, source, target, cost, reverse_cost FROM ways";

        // ST_Length(the_geom::geography) — 실제 미터 단위 거리 (cost 컬럼 단위에 무관)
        String dijkstraSql = """
                SELECT
                    v.lat,
                    v.lon,
                    ST_Length(w.the_geom::geography) AS edge_length_m,
                    w.safety_score
                FROM pgr_dijkstra('%s', :source, :target, directed := false) r
                JOIN ways_vertices_pgr v ON r.node = v.id
                LEFT JOIN ways w ON r.edge = w.gid
                ORDER BY r.seq
                """.formatted(innerSql);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("source", sourceId)
                .addValue("target", targetId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dijkstraSql, params);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "두 지점 사이 경로를 찾을 수 없습니다");
        }

        List<Coordinate> coordinates = new ArrayList<>();
        double totalDistanceMeters = 0.0;
        double totalSafetyScore = 0.0;
        int edgeCount = 0;

        for (Map<String, Object> row : rows) {
            double lat = ((Number) row.get("lat")).doubleValue();
            double lon = ((Number) row.get("lon")).doubleValue();
            coordinates.add(new Coordinate(lat, lon));  // Coordinate(lat, lng)

            if (row.get("edge_length_m") != null) {
                totalDistanceMeters += ((Number) row.get("edge_length_m")).doubleValue();
            }
            if (row.get("safety_score") != null) {
                totalSafetyScore += ((Number) row.get("safety_score")).doubleValue();
                edgeCount++;
            }
        }

        int avgSafetyScore = edgeCount > 0 ? (int) Math.round(totalSafetyScore / edgeCount) : 70;

        return new RouteResponse(coordinates, totalDistanceMeters, avgSafetyScore);
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

        Map<String, Object> row = jdbcTemplate.queryForMap(sql, params);
        double distM = ((Number) row.get("dist_m")).doubleValue();

        if (distM > MAX_SNAP_DISTANCE_M) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "경로 탐색 가능 구역이 아닙니다");
        }

        return ((Number) row.get("id")).longValue();
    }
}
