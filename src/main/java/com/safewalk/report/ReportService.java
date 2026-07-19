package com.safewalk.report;

import com.safewalk.report.dto.ReportRequest;
import com.safewalk.report.dto.ReportResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class ReportService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReportService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReportResponse save(ReportRequest request) {
        String sql = """
                INSERT INTO report (content, category, severity, geom)
                VALUES (:content, :category, :severity, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326))
                RETURNING id, content, category, severity, status,
                          ST_Y(geom) AS lat, ST_X(geom) AS lng, created_at
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("content", request.content())
                .addValue("category", request.category())
                .addValue("severity", request.severity())
                .addValue("lat", request.lat())
                .addValue("lng", request.lng());
        return jdbcTemplate.queryForObject(sql, params, ReportService::mapRow);
    }

    public List<ReportResponse> findAll() {
        String sql = """
                SELECT id, content, category, severity, status,
                       ST_Y(geom) AS lat, ST_X(geom) AS lng, created_at
                FROM report
                ORDER BY created_at DESC
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource(), ReportService::mapRow);
    }

    private static ReportResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReportResponse(
                rs.getLong("id"),
                rs.getString("content"),
                rs.getString("category"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null
        );
    }
}
