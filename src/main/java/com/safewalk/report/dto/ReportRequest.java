package com.safewalk.report.dto;

public record ReportRequest(String content, double lat, double lng, String category, String severity) {
}
