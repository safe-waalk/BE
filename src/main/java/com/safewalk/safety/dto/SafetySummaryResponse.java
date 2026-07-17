package com.safewalk.safety.dto;

public record SafetySummaryResponse(
        InfraSummary cctv,
        InfraSummary securityLight,
        InfraSummary safetyBell,
        CrimeZoneSummary crimeZone
) {
}
