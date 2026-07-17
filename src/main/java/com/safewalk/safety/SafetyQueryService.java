package com.safewalk.safety;

import com.safewalk.safety.dto.CrimeZoneSummary;
import com.safewalk.safety.dto.InfraSummary;
import com.safewalk.safety.dto.SafetySummaryResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;

@Service
public class SafetyQueryService {

    private static final double CCTV_RADIUS_M = 150.0;
    private static final double SECURITY_LIGHT_RADIUS_M = 100.0;
    private static final double SAFETY_BELL_RADIUS_M = 100.0;
    private static final double PUBLIC_OFFICE_RADIUS_M = 300.0;
    private static final double CRIME_ZONE_RADIUS_M = 150.0;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SafetyQueryService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SafetySummaryResponse getSummary(double lat, double lng) {
        return new SafetySummaryResponse(
                queryInfra("cctv", lat, lng, CCTV_RADIUS_M),
                queryInfra("security_light", lat, lng, SECURITY_LIGHT_RADIUS_M),
                queryInfra("safety_bell", lat, lng, SAFETY_BELL_RADIUS_M),
                queryInfra("public_office", lat, lng, PUBLIC_OFFICE_RADIUS_M),
                queryCrimeZone(lat, lng, CRIME_ZONE_RADIUS_M)
        );
    }

    private InfraSummary queryInfra(String table, double lat, double lng, double radiusMeters) {
        // table은 이 클래스 내부 상수 호출로만 전달되며 외부 입력을 받지 않으므로 SQL Injection 위험 없음
        String sql = "SELECT COUNT(*) AS cnt, "
                + "MIN(ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)) AS nearest "
                + "FROM " + table + " "
                + "WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radius", radiusMeters);

        return jdbcTemplate.queryForObject(sql, params, (ResultSet rs, int rowNum) -> new InfraSummary(
                rs.getInt("cnt"),
                rs.getObject("nearest") == null ? null : rs.getDouble("nearest")
        ));
    }

    private CrimeZoneSummary queryCrimeZone(double lat, double lng, double radiusMeters) {
        String sql = "SELECT COUNT(*) AS cnt, "
                + "MIN(ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)) AS nearest, "
                + "MAX(grade) AS max_grade "
                + "FROM crime_zone "
                + "WHERE ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius)";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radius", radiusMeters);

        return jdbcTemplate.queryForObject(sql, params, (ResultSet rs, int rowNum) -> new CrimeZoneSummary(
                rs.getInt("cnt"),
                rs.getObject("nearest") == null ? null : rs.getDouble("nearest"),
                rs.getObject("max_grade") == null ? null : rs.getInt("max_grade")
        ));
    }
}
