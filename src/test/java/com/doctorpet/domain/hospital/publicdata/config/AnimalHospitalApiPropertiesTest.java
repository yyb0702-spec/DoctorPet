package com.doctorpet.domain.hospital.publicdata.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnimalHospitalApiPropertiesTest {

    @Test
    void 로컬_설정이_없으면_안전한_공통_기본값을_사용한다() {
        AnimalHospitalApiProperties properties =
                new AnimalHospitalApiProperties(
                        null,
                        null,
                        0,
                        List.of(),
                        null,
                        null,
                        null
                );

        // 비밀값인 인증키를 제외한 API 주소와 타임아웃만 기본값으로 보완합니다.
        assertThat(properties.baseUrl())
                .isEqualTo(
                        "https://apis.data.go.kr/1741000/animal_hospitals"
                );
        assertThat(properties.connectTimeout())
                .isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.sourceCrs()).isEqualTo("EPSG:5174");
        assertThat(properties.serviceKey()).isNull();
    }
}
