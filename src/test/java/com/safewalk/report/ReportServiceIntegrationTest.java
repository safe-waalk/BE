package com.safewalk.report;

import com.safewalk.report.dto.ReportRequest;
import com.safewalk.report.dto.ReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReportServiceIntegrationTest {

    @Autowired
    private ReportService reportService;

    @Test
    void savedReportAppearsInList() {
        ReportRequest request = new ReportRequest("골목 가로등 없음", 37.5665, 126.9780, "LIGHTING", "HIGH");
        ReportResponse saved = reportService.save(request);

        assertThat(saved.id()).isPositive();
        assertThat(saved.content()).isEqualTo("골목 가로등 없음");
        assertThat(saved.status()).isEqualTo("PENDING");
        assertThat(saved.category()).isEqualTo("LIGHTING");
        assertThat(saved.severity()).isEqualTo("HIGH");

        List<ReportResponse> all = reportService.findAll();
        assertThat(all).anyMatch(r -> r.id() == saved.id());
    }

    @Test
    void nullCategoryAndSeverityAreAllowed() {
        ReportRequest request = new ReportRequest("설명만 있음", 37.5, 127.0, null, null);
        ReportResponse saved = reportService.save(request);

        assertThat(saved.category()).isNull();
        assertThat(saved.severity()).isNull();
        assertThat(saved.status()).isEqualTo("PENDING");
    }

    @Test
    void latLngRoundTripFromGeom() {
        double lat = 37.123456;
        double lng = 127.654321;
        ReportRequest request = new ReportRequest("좌표 테스트", lat, lng, null, null);
        ReportResponse saved = reportService.save(request);

        assertThat(saved.lat()).isEqualTo(lat);
        assertThat(saved.lng()).isEqualTo(lng);
    }
}
