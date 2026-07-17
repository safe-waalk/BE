package com.safewalk.safety.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafetySummaryResponseSerializationTest {

    @Test
    void serializesWithExpectedFieldNames() throws Exception {
        SafetySummaryResponse response = new SafetySummaryResponse(
                new InfraSummary(12, 45.2),
                new InfraSummary(8, 20.1),
                new InfraSummary(1, 180.4),
                new InfraSummary(0, null),
                new CrimeZoneSummary(2, 60.0, 7)
        );

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"cctv\"");
        assertThat(json).contains("\"securityLight\"");
        assertThat(json).contains("\"safetyBell\"");
        assertThat(json).contains("\"publicOffice\"");
        assertThat(json).contains("\"crimeZone\"");
        assertThat(json).contains("\"nearestDistance\":45.2");
        assertThat(json).contains("\"maxGrade\":7");
    }
}
