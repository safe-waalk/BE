package com.safewalk.climate;

import com.safewalk.climate.dto.FloodZone;
import com.safewalk.route.dto.Coordinate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FloodZoneAssemblerTest {

    @Test
    void groupsPointsIntoZonePolygonRingHierarchy() {
        FloodZoneAssembler assembler = new FloodZoneAssembler();
        // zone 7: 폴리곤 1(외곽 링 + 구멍 링), 폴리곤 2(외곽 링)
        assembler.addPoint(7, 2022, 1, 1, 0.0, 0.0);
        assembler.addPoint(7, 2022, 1, 1, 0.0, 1.0);
        assembler.addPoint(7, 2022, 1, 1, 1.0, 1.0);
        assembler.addPoint(7, 2022, 1, 1, 0.0, 0.0);
        assembler.addPoint(7, 2022, 1, 2, 0.2, 0.2);
        assembler.addPoint(7, 2022, 1, 2, 0.2, 0.3);
        assembler.addPoint(7, 2022, 1, 2, 0.3, 0.3);
        assembler.addPoint(7, 2022, 1, 2, 0.2, 0.2);
        assembler.addPoint(7, 2022, 2, 1, 5.0, 5.0);
        assembler.addPoint(7, 2022, 2, 1, 5.0, 6.0);
        assembler.addPoint(7, 2022, 2, 1, 6.0, 6.0);
        assembler.addPoint(7, 2022, 2, 1, 5.0, 5.0);
        // zone 9: 폴리곤 1(외곽 링), year 없음
        assembler.addPoint(9, null, 1, 1, 2.0, 2.0);
        assembler.addPoint(9, null, 1, 1, 2.0, 3.0);
        assembler.addPoint(9, null, 1, 1, 3.0, 3.0);
        assembler.addPoint(9, null, 1, 1, 2.0, 2.0);

        List<FloodZone> zones = assembler.zones();

        assertThat(zones).hasSize(2);

        FloodZone first = zones.get(0);
        assertThat(first.id()).isEqualTo(7);
        assertThat(first.year()).isEqualTo(2022);
        assertThat(first.polygons()).hasSize(2);
        assertThat(first.polygons().get(0)).hasSize(2);
        assertThat(first.polygons().get(0).get(0)).containsExactly(
                new Coordinate(0.0, 0.0), new Coordinate(0.0, 1.0),
                new Coordinate(1.0, 1.0), new Coordinate(0.0, 0.0));
        assertThat(first.polygons().get(0).get(1)).hasSize(4);
        assertThat(first.polygons().get(1)).hasSize(1);
        assertThat(first.polygons().get(1).get(0).get(1)).isEqualTo(new Coordinate(5.0, 6.0));

        FloodZone second = zones.get(1);
        assertThat(second.id()).isEqualTo(9);
        assertThat(second.year()).isNull();
        assertThat(second.polygons()).hasSize(1);
        assertThat(second.polygons().get(0)).hasSize(1);
        assertThat(second.polygons().get(0).get(0)).hasSize(4);
    }

    @Test
    void noPointsMeansNoZones() {
        assertThat(new FloodZoneAssembler().zones()).isEmpty();
    }
}
