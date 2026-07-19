package com.safewalk.safety;

import com.safewalk.safety.dto.CrimeZoneSummary;
import com.safewalk.safety.dto.InfraSummary;
import com.safewalk.safety.dto.SafetyScoreResponse;
import com.safewalk.safety.dto.SafetySummaryResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyScoreCalculatorTest {

    private final SafetyScoreCalculator calculator = new SafetyScoreCalculator();

    @Test
    void noCrimeZoneNoInfraReturnsBaseScore() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.score()).isEqualTo(70);
        assertThat(result.crimePenalty()).isEqualTo(0.0);
        assertThat(result.cctvBonus()).isEqualTo(0.0);
        assertThat(result.lightBonus()).isEqualTo(0.0);
    }

    @Test
    void maxCrimeGradeWithNoInfraYieldsWorstRealisticScore() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(1, 50.0, 10)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.crimePenalty()).isEqualTo(35.0);
        assertThat(result.score()).isEqualTo(35);
    }

    @Test
    void maxBonusesClampScoreAt100() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(10, 0.0),
                new InfraSummary(10, 0.0),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.cctvBonus()).isEqualTo(25.0);
        assertThat(result.lightBonus()).isEqualTo(20.0);
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void cctvNearestBonusScalesWithDistanceWithinRadius() {
        SafetySummaryResponse summary = new SafetySummaryResponse(
                new InfraSummary(0, 75.0),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );

        SafetyScoreResponse result = calculator.calculate(summary);

        assertThat(result.cctvBonus()).isEqualTo(7.5);
    }
}
