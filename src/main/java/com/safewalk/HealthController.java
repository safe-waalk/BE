package com.safewalk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

	private final JdbcTemplate jdbcTemplate;

	public HealthController(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@GetMapping("/api/health/db")
	public Map<String, Object> checkDb() {
		String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
		Integer crimeZoneCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crime_zone", Integer.class);
		return Map.of("status", "ok", "postgres_version", version, "crime_zone_count", crimeZoneCount);
	}

	@GetMapping("/api/health/db/counts")
	public Map<String, Object> checkTableCounts() {
		String[] tables = {"crime_zone", "cctv", "security_light", "safety_bell", "public_office", "report"};
		Map<String, Object> counts = new java.util.LinkedHashMap<>();
		for (String table : tables) {
			Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
			counts.put(table, count);
		}
		return counts;
	}
}
