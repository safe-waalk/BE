package com.safewalk.climate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClimateAlertController.class)
class ClimateAlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void noneScenarioRecommendsShortestRoute() throws Exception {
        mockMvc.perform(get("/api/climate/alert").param("scenario", "NONE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("NONE"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.recommendedMode").value("SHORTEST"))
                .andExpect(jsonPath("$.simulated").value(true));
    }

    @Test
    void heatScenarioGuidesAlongShadeAndShelters() throws Exception {
        mockMvc.perform(get("/api/climate/alert").param("scenario", "HEAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("HEAT"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.message").value(containsString("그늘")))
                .andExpect(jsonPath("$.message").value(containsString("무더위쉼터")))
                .andExpect(jsonPath("$.message").value(not(containsString("멈추"))))
                .andExpect(jsonPath("$.recommendedMode").value("HEAT"))
                .andExpect(jsonPath("$.simulated").value(true));
    }

    @Test
    void rainScenarioGuidesAroundFloodZonesAndUnderpasses() throws Exception {
        mockMvc.perform(get("/api/climate/alert").param("scenario", "RAIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("RAIN"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.message").value(containsString("침수")))
                .andExpect(jsonPath("$.message").value(containsString("지하차도")))
                .andExpect(jsonPath("$.message").value(not(containsString("멈추"))))
                .andExpect(jsonPath("$.recommendedMode").value("RAIN"))
                .andExpect(jsonPath("$.simulated").value(true));
    }

    @Test
    void unknownScenarioReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/alert").param("scenario", "SNOW"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lowercaseScenarioReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/alert").param("scenario", "heat"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingScenarioReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/alert"))
                .andExpect(status().isBadRequest());
    }
}
