package com.safewalk.safety;

import com.safewalk.safety.dto.SafetyScoreResponse;
import com.safewalk.safety.dto.SafetySummaryResponse;

public class SafetyScoreCalculator {

    private static final double BASE_SCORE = 70.0;
    private static final double CRIME_MAX_PENALTY = 35.0;
    private static final double CRIME_MAX_GRADE = 10.0;

    private static final double CCTV_NEAREST_RADIUS_M = 150.0;
    private static final double CCTV_NEAREST_MAX_BONUS = 15.0;
    private static final double CCTV_DENSITY_CAP_COUNT = 6.0;
    private static final double CCTV_DENSITY_MAX_BONUS = 10.0;

    private static final double LIGHT_NEAREST_RADIUS_M = 100.0;
    private static final double LIGHT_NEAREST_MAX_BONUS = 10.0;
    private static final double LIGHT_DENSITY_CAP_COUNT = 8.0;
    private static final double LIGHT_DENSITY_MAX_BONUS = 10.0;

    public SafetyScoreResponse calculate(SafetySummaryResponse summary) {
        double crimePenalty = calculateCrimePenalty(summary.crimeZone().maxGrade());
        double cctvBonus = calculateNearestBonus(summary.cctv().nearestDistance(), CCTV_NEAREST_RADIUS_M, CCTV_NEAREST_MAX_BONUS)
                + calculateDensityBonus(summary.cctv().count(), CCTV_DENSITY_CAP_COUNT, CCTV_DENSITY_MAX_BONUS);
        double lightBonus = calculateNearestBonus(summary.securityLight().nearestDistance(), LIGHT_NEAREST_RADIUS_M, LIGHT_NEAREST_MAX_BONUS)
                + calculateDensityBonus(summary.securityLight().count(), LIGHT_DENSITY_CAP_COUNT, LIGHT_DENSITY_MAX_BONUS);

        double rawScore = BASE_SCORE - crimePenalty + cctvBonus + lightBonus;
        int score = (int) Math.round(clamp(rawScore, 0.0, 100.0));

        return new SafetyScoreResponse(score, crimePenalty, cctvBonus, lightBonus);
    }

    private double calculateCrimePenalty(Integer maxGrade) {
        double normalized = maxGrade == null ? 0.0 : maxGrade / CRIME_MAX_GRADE;
        return normalized * CRIME_MAX_PENALTY;
    }

    private double calculateNearestBonus(Double nearestDistance, double radiusMeters, double maxBonus) {
        if (nearestDistance == null) {
            return 0.0;
        }
        return clamp((radiusMeters - nearestDistance) / radiusMeters, 0.0, 1.0) * maxBonus;
    }

    private double calculateDensityBonus(int count, double capCount, double maxBonus) {
        return clamp(count / capCount, 0.0, 1.0) * maxBonus;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
