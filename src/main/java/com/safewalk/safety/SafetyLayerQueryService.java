package com.safewalk.safety;

import com.safewalk.safety.dto.CctvPoint;
import com.safewalk.safety.dto.CrimeZonePoint;
import com.safewalk.safety.dto.SafetyBellPoint;
import com.safewalk.safety.dto.SafetyLayerResponse;
import com.safewalk.safety.dto.SecurityLightPoint;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

@Service
public class SafetyLayerQueryService {

    private static final int LAYER_POINT_LIMIT = 500;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SafetyLayerQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SafetyLayerResponse getLayers(double swLat, double swLng, double neLat, double neLng, Set<String> layers) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("swLat", swLat)
                .addValue("swLng", swLng)
                .addValue("neLat", neLat)
                .addValue("neLng", neLng);

        List<CctvPoint> cctv = layers.contains("cctv") ? queryCctv(params) : null;
        List<SecurityLightPoint> securityLight = layers.contains("securityLight") ? querySecurityLight(params) : null;
        List<SafetyBellPoint> safetyBell = layers.contains("safetyBell") ? querySafetyBell(params) : null;
        List<CrimeZonePoint> crimeZone = layers.contains("crimeZone") ? queryCrimeZone(params) : null;

        return new SafetyLayerResponse(cctv, securityLight, safetyBell, crimeZone);
    }

    private List<CctvPoint> queryCctv(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, address, camera_count "
                + "FROM cctv "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new CctvPoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("address"),
                rs.getInt("camera_count")
        ));
    }

    private List<SecurityLightPoint> querySecurityLight(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, address "
                + "FROM security_light "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new SecurityLightPoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("address")
        ));
    }

    private List<SafetyBellPoint> querySafetyBell(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, address "
                + "FROM safety_bell "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new SafetyBellPoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("address")
        ));
    }

    private List<CrimeZonePoint> queryCrimeZone(MapSqlParameterSource params) {
        String sql = "SELECT id, ST_Y(geom) AS lat, ST_X(geom) AS lng, grade "
                + "FROM crime_zone "
                + "WHERE geom && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326) "
                + "LIMIT " + LAYER_POINT_LIMIT;

        return jdbcTemplate.query(sql, params, (ResultSet rs, int rowNum) -> new CrimeZonePoint(
                rs.getLong("id"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getInt("grade")
        ));
    }
}
