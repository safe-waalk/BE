package com.safewalk.route;

import com.safewalk.route.dto.Coordinate;
import com.safewalk.route.dto.RouteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
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
}
