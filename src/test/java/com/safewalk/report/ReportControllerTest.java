package com.safewalk.report;

import com.safewalk.report.dto.ReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @Test
    void blankContentReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"","lat":37.5,"lng":127.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void latOutOfRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":91.0,"lng":127.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lngOutOfRangeReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":37.5,"lng":181.0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidCategoryReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":37.5,"lng":127.0,"category":"BLAH"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidSeverityReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"test","lat":37.5,"lng":127.0,"severity":"CRITICAL"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validPostReturns201WithBody() throws Exception {
        when(reportService.save(any())).thenReturn(new ReportResponse(
                1L, "골목 가로등 없음", "LIGHTING", "HIGH", "PENDING",
                37.5665, 126.9780, LocalDateTime.of(2026, 7, 19, 12, 0)));

        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"골목 가로등 없음","lat":37.5665,"lng":126.9780,"category":"LIGHTING","severity":"HIGH"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.category").value("LIGHTING"))
                .andExpect(jsonPath("$.severity").value("HIGH"));
    }
}
