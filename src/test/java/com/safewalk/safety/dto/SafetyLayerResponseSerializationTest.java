package com.safewalk.safety.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SafetyLayerResponseSerializationTest {

    @Test
    void omitsKeysForLayersNotRequested() throws Exception {
        SafetyLayerResponse response = new SafetyLayerResponse(
                List.of(new CctvPoint(1, 37.5665, 126.9780, "서울 중구", 2)),
                null,
                null,
                List.of(new CrimeZonePoint(3, 37.5550, 126.9700, 7))
        );

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"cctv\"");
        assertThat(json).contains("\"crimeZone\"");
        assertThat(json).doesNotContain("\"securityLight\"");
        assertThat(json).doesNotContain("\"safetyBell\"");
        assertThat(json).contains("\"cameraCount\":2");
        assertThat(json).contains("\"grade\":7");
    }
}
