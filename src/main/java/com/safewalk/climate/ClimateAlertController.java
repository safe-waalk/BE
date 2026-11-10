package com.safewalk.climate;

import com.safewalk.climate.dto.ClimateAlertResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ClimateAlertController {

    @GetMapping("/api/climate/alert")
    public ClimateAlertResponse getAlert(@RequestParam String scenario) {
        return AlertScenario.from(scenario)
                .map(AlertScenario::toResponse)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "scenario는 NONE, HEAT, RAIN 중 하나여야 합니다"));
    }
}
