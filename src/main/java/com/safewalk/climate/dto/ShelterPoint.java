package com.safewalk.climate.dto;

public record ShelterPoint(
        long id,
        double lat,
        double lng,
        String name,
        String facilityType,
        String address,
        String openTime,
        String closeTime) {
}
