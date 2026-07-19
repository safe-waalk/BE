package com.safewalk.safety;

import com.safewalk.safety.dto.CrimeZoneSummary;
import com.safewalk.safety.dto.InfraSummary;
import com.safewalk.safety.dto.SafetySummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SafetyScoreController.class)
class SafetyScoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SafetyQueryService safetyQueryService;

    @Test
    void missingLatReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/score").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/score").param("lat", "91").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lngOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/score").param("lat", "37.5").param("lng", "181"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestReturnsComputedScore() throws Exception {
        SafetySummaryResponse mockSummary = new SafetySummaryResponse(
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new InfraSummary(0, null),
                new CrimeZoneSummary(0, null, null)
        );
        when(safetyQueryService.getSummary(anyDouble(), anyDouble())).thenReturn(mockSummary);

        mockMvc.perform(get("/api/safety/score").param("lat", "37.5665").param("lng", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(70))
                .andExpect(jsonPath("$.crimePenalty").value(0.0))
                .andExpect(jsonPath("$.cctvBonus").value(0.0))
                .andExpect(jsonPath("$.lightBonus").value(0.0));
    }
}
