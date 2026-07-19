package com.safewalk.report;

import com.safewalk.report.dto.ReportRequest;
import com.safewalk.report.dto.ReportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
public class ReportController {

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "LIGHTING", "CCTV", "SUSPICIOUS_AREA", "ROAD_ENVIRONMENT",
            "CRIME_RISK", "NOISE_GROUP", "WOMEN_SAFETY", "FALSE_REPORT",
            "PRIVACY_RISK", "ETC"
    );
    private static final Set<String> VALID_SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/api/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse createReport(@RequestBody ReportRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        if (request.lat() < -90 || request.lat() > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat must be between -90 and 90");
        }
        if (request.lng() < -180 || request.lng() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lng must be between -180 and 180");
        }
        if (request.category() != null && !VALID_CATEGORIES.contains(request.category())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid category: " + request.category());
        }
        if (request.severity() != null && !VALID_SEVERITIES.contains(request.severity())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "severity must be HIGH, MEDIUM, or LOW");
        }
        return reportService.save(request);
    }

    @GetMapping("/api/reports")
    public List<ReportResponse> listReports() {
        throw new UnsupportedOperationException("implemented in Task 2");
    }
}