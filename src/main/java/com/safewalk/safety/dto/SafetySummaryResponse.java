package com.safewalk.safety.dto;

public record SafetySummaryResponse(
        InfraSummary cctv,
        InfraSummary securityLight,
        InfraSummary safetyBell,
        InfraSummary publicOffice,
        CrimeZoneSummary crimeZone
) {
}
