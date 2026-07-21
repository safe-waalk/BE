package com.safewalk.route;

import com.safewalk.route.dto.RouteResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/route")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping
    public RouteResponse getRoute(
            @RequestParam double startLat,
            @RequestParam double startLng,
            @RequestParam double endLat,
            @RequestParam double endLng,
            @RequestParam String mode) {

        if (startLat < -90 || startLat > 90 || endLat < -90 || endLat > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "위도는 -90~90 사이여야 합니다");
        }
        if (startLng < -180 || startLng > 180 || endLng < -180 || endLng > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "경도는 -180~180 사이여야 합니다");
        }
        if (!mode.equals("SAFE") && !mode.equals("SHORTEST")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode는 SAFE 또는 SHORTEST여야 합니다");
        }

        return routeService.findRoute(startLat, startLng, endLat, endLng, mode);
    }
}
