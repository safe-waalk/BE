package com.safewalk.climate.dto;

/**
 * @param simulated 기상청 특보 API가 아닌 시나리오 시뮬레이션이면 true. 현재는 항상 true.
 */
public record ClimateAlertResponse(
        String type,
        String title,
        String message,
        String recommendedMode,
        boolean simulated) {
}
