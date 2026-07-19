package com.safewalk.report.dto;

import java.time.LocalDateTime;

public record ReportResponse(long id, String content, String category, String severity,
                              String status, double lat, double lng, LocalDateTime createdAt) {
}
