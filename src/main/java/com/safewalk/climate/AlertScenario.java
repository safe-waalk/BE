package com.safewalk.climate;

import com.safewalk.climate.dto.ClimateAlertResponse;
import com.safewalk.route.RouteMode;

import java.util.Arrays;
import java.util.Optional;

/**
 * 기상 특보 시나리오. 기상청 특보 API 키가 없어 시나리오를 골라 시뮬레이션한다.
 * 문구는 "멈추세요"가 아니라 "이쪽으로 가세요" — 어떤 길로 가면 되는지 안내한다.
 */
enum AlertScenario {
    NONE(
            "평소 경로 안내",
            "지금은 발효된 기상 특보가 없어요. 가장 짧은 길로 안내할게요.",
            RouteMode.SHORTEST),
    HEAT(
            "폭염 특보 · 그늘길로 안내해요",
            "폭염 특보가 내려졌어요. 그늘이 많은 길과 무더위쉼터를 지나는 경로로 안내할게요. "
                    + "더위가 심하면 경로 옆 무더위쉼터에 들러 쉬어 가세요.",
            RouteMode.HEAT),
    RAIN(
            "호우 특보 · 침수 걱정 없는 길로 안내해요",
            "호우 특보가 내려졌어요. 침수 이력이 있는 구간과 지하차도를 피해 가는 경로로 안내할게요. "
                    + "안내하는 길을 따라 걸어가세요.",
            RouteMode.RAIN);

    private final String title;
    private final String message;
    private final RouteMode recommendedMode;

    AlertScenario(String title, String message, RouteMode recommendedMode) {
        this.title = title;
        this.message = message;
        this.recommendedMode = recommendedMode;
    }

    static Optional<AlertScenario> from(String scenario) {
        return Arrays.stream(values()).filter(s -> s.name().equals(scenario)).findFirst();
    }

    ClimateAlertResponse toResponse() {
        return new ClimateAlertResponse(name(), title, message, recommendedMode.name(), true);
    }
}
