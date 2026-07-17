package com.safewalk.safety;

import com.safewalk.safety.dto.SafetySummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SafetyController {

    private final SafetyQueryService safetyQueryService;

    public SafetyController(SafetyQueryService safetyQueryService) {
        this.safetyQueryService = safetyQueryService;
    }

    @GetMapping("/api/safety/summary")
    public SafetySummaryResponse getSummary(@RequestParam double lat, @RequestParam double lng) {
        if (lat < -90 || lat > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat must be between -90 and 90");
        }
        if (lng < -180 || lng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lng must be between -180 and 180");
        }
        return safetyQueryService.getSummary(lat, lng);
    }
}
