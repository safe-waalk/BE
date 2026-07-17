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

@WebMvcTest(SafetyController.class)
class SafetyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SafetyQueryService safetyQueryService;

    @Test
    void missingLatReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/summary").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/summary").param("lat", "91").param("lng", "127.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestReturnsSummary() throws Exception {
        SafetySummaryResponse mockResponse = new SafetySummaryResponse(
                new InfraSummary(12, 45.2),
                new InfraSummary(8, 20.1),
                new InfraSummary(1, 180.4),
                new InfraSummary(0, null),
                new CrimeZoneSummary(2, 60.0, 7)
        );
        when(safetyQueryService.getSummary(anyDouble(), anyDouble())).thenReturn(mockResponse);

        mockMvc.perform(get("/api/safety/summary").param("lat", "37.5665").param("lng", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cctv.count").value(12))
                .andExpect(jsonPath("$.crimeZone.maxGrade").value(7));
    }
}
