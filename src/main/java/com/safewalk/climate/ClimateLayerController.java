package com.safewalk.climate;

import com.safewalk.climate.dto.FloodZoneListResponse;
import com.safewalk.climate.dto.ShelterListResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ClimateLayerController {

    private static final double MAX_BOUNDS_METERS = 5000.0;
    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private final ClimateLayerQueryService climateLayerQueryService;

    public ClimateLayerController(ClimateLayerQueryService climateLayerQueryService) {
        this.climateLayerQueryService = climateLayerQueryService;
    }

    @GetMapping("/api/climate/shelters")
    public ShelterListResponse getShelters(
            @RequestParam double swLat,
            @RequestParam double swLng,
            @RequestParam double neLat,
            @RequestParam double neLng) {
        validateBounds(swLat, swLng, neLat, neLng);
        return climateLayerQueryService.getShelters(swLat, swLng, neLat, neLng);
    }

    @GetMapping("/api/climate/flood-zones")
    public FloodZoneListResponse getFloodZones(
            @RequestParam double swLat,
            @RequestParam double swLng,
            @RequestParam double neLat,
            @RequestParam double neLng) {
        validateBounds(swLat, swLng, neLat, neLng);
        return climateLayerQueryService.getFloodZones(swLat, swLng, neLat, neLng);
    }

    private void validateBounds(double swLat, double swLng, double neLat, double neLng) {
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
}
