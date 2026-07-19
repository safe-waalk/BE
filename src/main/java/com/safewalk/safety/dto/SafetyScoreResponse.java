package com.safewalk.safety.dto;

public record SafetyScoreResponse(int score, double crimePenalty, double cctvBonus, double lightBonus) {
}
