package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;
import com.safewalk.route.dto.RefugePoint;
import com.safewalk.route.dto.RouteExposure;
import com.safewalk.route.dto.RouteResponse;
import com.safewalk.route.dto.RouteShelter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RouteController.class)
class RouteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteService routeService;

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/route")
                        .param("startLat", "91.0")
                        .param("startLng", "126.978")
                        .param("endLat", "37.56")
                        .param("endLng", "126.97")
                        .param("mode", "SAFE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lngOutOfRangeReturns400() throws Exception {
        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5665")
                        .param("startLng", "181.0")
                        .param("endLat", "37.56")
                        .param("endLng", "126.97")
                        .param("mode", "SAFE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidModeReturns400() throws Exception {
        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5665")
                        .param("startLng", "126.978")
                        .param("endLat", "37.56")
                        .param("endLng", "126.97")
                        .param("mode", "FAST"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nodeNotFoundReturns404() throws Exception {
        when(routeService.findRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "경로 탐색 가능 구역이 아닙니다"));

        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5665")
                        .param("startLng", "126.978")
                        .param("endLat", "37.56")
                        .param("endLng", "126.97")
                        .param("mode", "SAFE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noPathFoundReturns404() throws Exception {
        when(routeService.findRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "두 지점 사이 경로를 찾을 수 없습니다"));

        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5665")
                        .param("startLng", "126.978")
                        .param("endLat", "37.56")
                        .param("endLng", "126.97")
                        .param("mode", "SAFE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validSafeRequestReturns200WithBody() throws Exception {
        when(routeService.findRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("SAFE")))
                .thenReturn(new RouteResponse(
                        List.of(
                                new Coordinate(37.5665, 126.978),
                                new Coordinate(37.56, 126.97)),
                        1234.5,
                        78));

        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5665")
                        .param("startLng", "126.978")
                        .param("endLat", "37.56")
                        .param("endLng", "126.97")
                        .param("mode", "SAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinates").isArray())
                .andExpect(jsonPath("$.totalDistanceMeters").isNumber())
                .andExpect(jsonPath("$.estimatedSafetyScore").isNumber());
    }

    @Test
    void validHeatRequestReturnsExposureAndShelters() throws Exception {
        when(routeService.findRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("HEAT")))
                .thenReturn(new RouteResponse(
                        List.of(
                                new Coordinate(37.5884, 127.0062),
                                new Coordinate(37.5826, 127.0103)),
                        950.0,
                        70,
                        new RouteExposure(0.42, 0.0, 0.0),
                        List.of(new RouteShelter(11L, "삼선동 경로당", 37.5860, 127.0080, 23.4)),
                        null));

        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5884")
                        .param("startLng", "127.0062")
                        .param("endLat", "37.5826")
                        .param("endLng", "127.0103")
                        .param("mode", "HEAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinates[0].lat").value(37.5884))
                .andExpect(jsonPath("$.coordinates[0].lng").value(127.0062))
                .andExpect(jsonPath("$.totalDistanceMeters").value(950.0))
                .andExpect(jsonPath("$.estimatedSafetyScore").value(70))
                .andExpect(jsonPath("$.exposure.shadeRatio").value(0.42))
                .andExpect(jsonPath("$.exposure.floodZoneMeters").value(0.0))
                .andExpect(jsonPath("$.exposure.underpassMeters").value(0.0))
                .andExpect(jsonPath("$.shelters[0].id").value(11))
                .andExpect(jsonPath("$.shelters[0].name").value("삼선동 경로당"))
                .andExpect(jsonPath("$.shelters[0].lat").value(37.5860))
                .andExpect(jsonPath("$.shelters[0].lng").value(127.0080))
                .andExpect(jsonPath("$.shelters[0].distanceFromRouteM").value(23.4))
                .andExpect(jsonPath("$.refuge").doesNotExist());
    }

    @Test
    void validRainRequestReturnsRefugeAndEmptyShelters() throws Exception {
        when(routeService.findRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("RAIN")))
                .thenReturn(new RouteResponse(
                        List.of(
                                new Coordinate(37.5884, 127.0062),
                                new Coordinate(37.5826, 127.0103)),
                        1010.0,
                        70,
                        new RouteExposure(0.1, 0.0, 0.0),
                        List.of(),
                        new RefugePoint(37.5900, 127.0100, 48.2, 210.0)));

        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5884")
                        .param("startLng", "127.0062")
                        .param("endLat", "37.5826")
                        .param("endLng", "127.0103")
                        .param("mode", "RAIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinates").isArray())
                .andExpect(jsonPath("$.exposure.floodZoneMeters").value(0.0))
                .andExpect(jsonPath("$.shelters").isArray())
                .andExpect(jsonPath("$.shelters").isEmpty())
                .andExpect(jsonPath("$.refuge.lat").value(37.5900))
                .andExpect(jsonPath("$.refuge.lng").value(127.0100))
                .andExpect(jsonPath("$.refuge.elevationM").value(48.2))
                .andExpect(jsonPath("$.refuge.distanceM").value(210.0));
    }

    @Test
    void lowercaseModeReturns400() throws Exception {
        mockMvc.perform(get("/api/route")
                        .param("startLat", "37.5884")
                        .param("startLng", "127.0062")
                        .param("endLat", "37.5826")
                        .param("endLng", "127.0103")
                        .param("mode", "heat"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(routeService);
    }
}
