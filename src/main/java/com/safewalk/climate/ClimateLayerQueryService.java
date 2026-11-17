package com.safewalk.climate;

import com.safewalk.climate.dto.FloodZoneListResponse;
import com.safewalk.climate.dto.ShelterListResponse;
import com.safewalk.climate.dto.ShelterPoint;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.List;

@Service
public class ClimateLayerQueryService {

    private static final int SHELTER_LIMIT = 500;
    private static final int FLOOD_ZONE_LIMIT = 300;
    // 4326 좌표 단위(도). 위도 37.5° 근처에서 약 2m — 지도 표시에 티 나지 않는 수준
    private static final double FLOOD_ZONE_SIMPLIFY_TOLERANCE_DEG = 0.00002;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ClimateLayerQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ShelterListResponse getShelters(double swLat, double swLng, double neLat, double neLng) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, "
                + "name, facility_type, address, open_time, close_time "
                + "FROM cooling_shelter "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "ORDER BY id "
                + "LIMIT " + SHELTER_LIMIT;

        List<ShelterPoint> shelters = jdbcTemplate.query(sql, boundsParams(swLat, swLng, neLat, neLng),
                (ResultSet rs, int rowNum) -> new ShelterPoint(
                        rs.getLong("id"),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        rs.getString("name"),
                        rs.getString("facility_type"),
                        rs.getString("address"),
                        rs.getString("open_time"),
                        rs.getString("close_time")
                ));
        return new ShelterListResponse(shelters);
    }

    /**
     * bbox와 겹치는 침수 흔적 폴리곤. 응답 크기를 줄이려고 bbox로 자르고(ST_Intersection)
     * 형상을 단순화한다(ST_SimplifyPreserveTopology). 자르면서 생긴 선·점 조각은 버린다(ST_CollectionExtract 3).
     */
    public FloodZoneListResponse getFloodZones(double swLat, double swLng, double neLat, double neLng) {
        String sql = """
                WITH bbox AS (
                    SELECT ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) AS geom
                ),
                zones AS (
                    SELECT
                        f.id, f.year,
                        ST_Multi(ST_SimplifyPreserveTopology(
                            ST_CollectionExtract(ST_Intersection(f.geom, bbox.geom), 3),
                            :tolerance)) AS geom
                    FROM flood_trace f, bbox
                    WHERE f.geom && bbox.geom
                      AND ST_Intersects(f.geom, bbox.geom)
                    ORDER BY f.id
                    LIMIT %d
                )
                SELECT
                    z.id, z.year,
                    p.path[1] AS polygon_index,
                    p.path[2] AS ring_index,
                    ST_Y(p.geom) AS lat,
                    ST_X(p.geom) AS lng
                FROM zones z
                CROSS JOIN LATERAL ST_DumpPoints(z.geom) AS p
                WHERE NOT ST_IsEmpty(z.geom)
                ORDER BY z.id, p.path[1], p.path[2], p.path[3]
                """.formatted(FLOOD_ZONE_LIMIT);

        MapSqlParameterSource params = boundsParams(swLat, swLng, neLat, neLng)
                .addValue("tolerance", FLOOD_ZONE_SIMPLIFY_TOLERANCE_DEG);

        FloodZoneAssembler assembler = new FloodZoneAssembler();
        jdbcTemplate.query(sql, params, (ResultSet rs) -> {
            assembler.addPoint(
                    rs.getLong("id"),
                    rs.getObject("year") == null ? null : rs.getInt("year"),
                    rs.getInt("polygon_index"),
                    rs.getInt("ring_index"),
                    rs.getDouble("lat"),
                    rs.getDouble("lng"));
        });
        return new FloodZoneListResponse(assembler.zones());
    }

    private MapSqlParameterSource boundsParams(double swLat, double swLng, double neLat, double neLng) {
        return new MapSqlParameterSource()
                .addValue("swLat", swLat)
                .addValue("swLng", swLng)
                .addValue("neLat", neLat)
                .addValue("neLng", neLng);
    }
}
