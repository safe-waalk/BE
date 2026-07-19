package com.safewalk.safety;

import com.safewalk.safety.dto.CctvPoint;
import com.safewalk.safety.dto.CrimeZonePoint;
import com.safewalk.safety.dto.SafetyLayerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SafetyLayerController.class)
class SafetyLayerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SafetyLayerQueryService safetyLayerQueryService;

    @Test
    void missingBoundsParamReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLng", "126.9").param("neLat", "37.6").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "-91").param("swLng", "126.9")
                        .param("neLat", "37.6").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invertedBoundsReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.6").param("swLng", "126.9")
                        .param("neLat", "37.5").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boundsExceedingMaxSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.0").param("swLng", "126.0")
                        .param("neLat", "38.0").param("neLng", "127.0")
                        .param("layers", "cctv"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownLayerNameReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.55").param("swLng", "126.97")
                        .param("neLat", "37.56").param("neLng", "126.98")
                        .param("layers", "notARealLayer"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingLayersParamReturns400() throws Exception {
        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.55").param("swLng", "126.97")
                        .param("neLat", "37.56").param("neLng", "126.98"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validRequestReturnsOnlyRequestedLayers() throws Exception {
        SafetyLayerResponse mockResponse = new SafetyLayerResponse(
                List.of(new CctvPoint(1, 37.5665, 126.9780, "서울 중구", 2)),
                null,
                null,
                List.of(new CrimeZonePoint(3, 37.555, 126.975, 7))
        );
        when(safetyLayerQueryService.getLayers(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anySet()))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/safety/layers")
                        .param("swLat", "37.55").param("swLng", "126.97")
                        .param("neLat", "37.56").param("neLng", "126.98")
                        .param("layers", "cctv,crimeZone"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cctv[0].cameraCount").value(2))
                .andExpect(jsonPath("$.crimeZone[0].grade").value(7))
                .andExpect(jsonPath("$.securityLight").doesNotExist())
                .andExpect(jsonPath("$.safetyBell").doesNotExist());
    }
}
