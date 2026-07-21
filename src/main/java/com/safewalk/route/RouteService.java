package com.safewalk.route;

import com.safewalk.route.dto.RouteResponse;
import org.springframework.stereotype.Service;

@Service
public class RouteService {

    public RouteResponse findRoute(
            double startLat, double startLng,
            double endLat, double endLng,
            String mode) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
