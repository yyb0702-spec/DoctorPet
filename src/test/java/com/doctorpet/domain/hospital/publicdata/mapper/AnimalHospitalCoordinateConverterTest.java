package com.doctorpet.domain.hospital.publicdata.mapper;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.model.Wgs84Coordinate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnimalHospitalCoordinateConverterTest {

    private final AnimalHospitalCoordinateConverter converter =
            new AnimalHospitalCoordinateConverter(properties());

    @Test
    void 마포구_투영좌표를_WGS84_경위도로_변환한다() {
        Wgs84Coordinate result = converter.convert(
                new BigDecimal("191409.240701388"),
                new BigDecimal("451377.18304308")
        );

        // 응답 예시의 마포구 병원이 서울 경위도 범위로 변환되는지 확인합니다.
        assertThat(result.longitude())
                .isBetween(new BigDecimal("126"), new BigDecimal("128"));
        assertThat(result.latitude())
                .isBetween(new BigDecimal("37"), new BigDecimal("38"));
        assertThat(result.longitude().scale()).isEqualTo(7);
        assertThat(result.latitude().scale()).isEqualTo(7);
    }

    @Test
    void 원본_좌표가_하나라도_없으면_경위도를_저장하지_않는다() {
        Wgs84Coordinate result = converter.convert(
                new BigDecimal("191409.240701388"),
                null
        );

        assertThat(result.longitude()).isNull();
        assertThat(result.latitude()).isNull();
    }

    private static AnimalHospitalApiProperties properties() {
        return new AnimalHospitalApiProperties(
                null,
                null,
                100,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                "EPSG:5174",
                3,
                Duration.ofMinutes(5)
        );
    }
}
