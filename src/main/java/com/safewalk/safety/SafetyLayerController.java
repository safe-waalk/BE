package com.safewalk.safety;

import com.safewalk.safety.dto.SafetyLayerResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.Set;

@RestController
public class SafetyLayerController {

    private static final Set<String> ALLOWED_LAYERS = Set.of("cctv", "securityLight", "safetyBell", "crimeZone");
    private static final double MAX_BOUNDS_METERS = 5000.0;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private final SafetyLayerQueryService safetyLayerQueryService;

    public SafetyLayerController(SafetyLayerQueryService safetyLayerQueryService) {
        this.safetyLayerQueryService = safetyLayerQueryService;
    }

    @GetMapping("/api/safety/layers")
    public SafetyLayerResponse getLayers(
            @RequestParam double swLat,
            @RequestParam double swLng,
            @RequestParam double neLat,
            @RequestParam double neLng,
            @RequestParam String layers) {
        validateCoordinate(swLat, -90, 90, "swLat");
        validateCoordinate(neLat, -90, 90, "neLat");
        validateCoordinate(swLng, -180, 180, "swLng");
        validateCoordinate(neLng, -180, 180, "neLng");

        if (swLat >= neLat || swLng >= neLng) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "swLat/swLng must be less than neLat/neLng");
        }

        if (exceedsMaxBoundsSize(swLat, swLng, neLat, neLng)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bounds must not exceed 5000m on either side");
        }

        Set<String> layerSet = parseLayers(layers);

        return safetyLayerQueryService.getLayers(swLat, swLng, neLat, neLng, layerSet);
    }

    private void validateCoordinate(double value, double min, double max, String name) {
        if (value < min || value > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be between " + min + " and " + max);
        }
    }

    private boolean exceedsMaxBoundsSize(double swLat, double swLng, double neLat, double neLng) {
        double latMeters = Math.abs(neLat - swLat) * METERS_PER_DEGREE_LAT;
        double avgLatRad = Math.toRadians((swLat + neLat) / 2.0);
        double lngMeters = Math.abs(neLng - swLng) * METERS_PER_DEGREE_LAT * Math.cos(avgLatRad);
        return latMeters > MAX_BOUNDS_METERS || lngMeters > MAX_BOUNDS_METERS;
    }

    private Set<String> parseLayers(String layers) {
        Set<String> result = new LinkedHashSet<>();
        for (String layer : layers.split(",")) {
            String trimmed = layer.trim();
            if (trimmed.isEmpty() || !ALLOWED_LAYERS.contains(trimmed)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layers must be a comma-separated list of: " + ALLOWED_LAYERS);
            }
            result.add(trimmed);
        }
        if (result.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layers must not be empty");
        }
        return result;
    }
}
