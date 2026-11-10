package com.safewalk.route.dto;

import java.util.List;

public record RouteResponse(
        List<Coordinate> coordinates,
        double totalDistanceMeters,
        int estimatedSafetyScore) {}
