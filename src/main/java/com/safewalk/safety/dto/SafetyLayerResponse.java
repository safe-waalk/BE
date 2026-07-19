package com.safewalk.safety.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SafetyLayerResponse(
        List<CctvPoint> cctv,
        List<SecurityLightPoint> securityLight,
        List<SafetyBellPoint> safetyBell,
        List<CrimeZonePoint> crimeZone
) {
}
