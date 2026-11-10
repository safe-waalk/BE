package com.safewalk.climate;

import com.safewalk.climate.dto.FloodZone;
import com.safewalk.climate.dto.FloodZoneListResponse;
import com.safewalk.climate.dto.ShelterListResponse;
import com.safewalk.climate.dto.ShelterPoint;
import com.safewalk.route.dto.Coordinate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClimateLayerController.class)
class ClimateLayerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClimateLayerQueryService climateLayerQueryService;

    // --- /api/climate/shelters ---

    @Test
    void sheltersMissingBoundsParamReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/shelters")
                        .param("swLng", "127.000").param("neLat", "37.592").param("neLng", "127.014"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sheltersLatOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/shelters")
                        .param("swLat", "-91").param("swLng", "127.000")
                        .param("neLat", "37.592").param("neLng", "127.014"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sheltersInvertedBoundsReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/shelters")
                        .param("swLat", "37.592").param("swLng", "127.000")
                        .param("neLat", "37.578").param("neLng", "127.014"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sheltersBoundsExceedingMaxSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/shelters")
                        .param("swLat", "37.0").param("swLng", "126.0")
                        .param("neLat", "38.0").param("neLng", "127.0"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(climateLayerQueryService);
    }

    @Test
    void validSheltersRequestReturns200() throws Exception {
        when(climateLayerQueryService.getShelters(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new ShelterListResponse(List.of(
                        new ShelterPoint(3, 37.5860, 127.0080, "삼선동 경로당", "경로당",
                                "서울특별시 성북구 삼선교로 1", "09:00", "18:00"))));

        mockMvc.perform(get("/api/climate/shelters")
                        .param("swLat", "37.578").param("swLng", "127.000")
                        .param("neLat", "37.592").param("neLng", "127.014"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelters[0].id").value(3))
                .andExpect(jsonPath("$.shelters[0].lat").value(37.5860))
                .andExpect(jsonPath("$.shelters[0].lng").value(127.0080))
                .andExpect(jsonPath("$.shelters[0].name").value("삼선동 경로당"))
                .andExpect(jsonPath("$.shelters[0].facilityType").value("경로당"))
                .andExpect(jsonPath("$.shelters[0].openTime").value("09:00"))
                .andExpect(jsonPath("$.shelters[0].closeTime").value("18:00"));
    }

    // --- /api/climate/flood-zones ---

    @Test
    void floodZonesMissingBoundsParamReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/flood-zones")
                        .param("swLat", "37.578").param("swLng", "127.000").param("neLat", "37.592"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void floodZonesLngOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/flood-zones")
                        .param("swLat", "37.578").param("swLng", "127.000")
                        .param("neLat", "37.592").param("neLng", "181"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void floodZonesInvertedBoundsReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/flood-zones")
                        .param("swLat", "37.578").param("swLng", "127.014")
                        .param("neLat", "37.592").param("neLng", "127.000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void floodZonesBoundsExceedingMaxSizeReturns400() throws Exception {
        mockMvc.perform(get("/api/climate/flood-zones")
                        .param("swLat", "37.50").param("swLng", "126.90")
                        .param("neLat", "37.60").param("neLng", "127.10"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(climateLayerQueryService);
    }

    @Test
    void validFloodZonesRequestReturnsNestedPolygonArrays() throws Exception {
        List<Coordinate> outer = List.of(
                new Coordinate(37.580, 127.002), new Coordinate(37.580, 127.004),
                new Coordinate(37.582, 127.004), new Coordinate(37.580, 127.002));
        when(climateLayerQueryService.getFloodZones(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new FloodZoneListResponse(List.of(
                        new FloodZone(5, 2022, List.of(List.of(outer))))));

        mockMvc.perform(get("/api/climate/flood-zones")
                        .param("swLat", "37.578").param("swLng", "127.000")
                        .param("neLat", "37.592").param("neLng", "127.014"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.floodZones[0].id").value(5))
                .andExpect(jsonPath("$.floodZones[0].year").value(2022))
                .andExpect(jsonPath("$.floodZones[0].polygons").isArray())
                .andExpect(jsonPath("$.floodZones[0].polygons[0]").isArray())
                .andExpect(jsonPath("$.floodZones[0].polygons[0][0]").isArray())
                .andExpect(jsonPath("$.floodZones[0].polygons[0][0].length()").value(4))
                .andExpect(jsonPath("$.floodZones[0].polygons[0][0][1].lat").value(37.580))
                .andExpect(jsonPath("$.floodZones[0].polygons[0][0][1].lng").value(127.004));
    }
}
