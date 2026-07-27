package com.doctorpet.domain.hospital.publicdata.infrastructure;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 동물병원 OpenAPI 연결과 기본 응답 구조를 검증합니다.
 */
class AnimalHospitalPublicDataClientTest {

    private static final String BASE_URL =
            "https://apis.data.go.kr/1741000/animal_hospitals";

    @Test
    @EnabledIfEnvironmentVariable(
            named = "PUBLIC_DATA_SERVICE_KEY",
            matches = ".+"
    )
    void fetchesFirstPageFromAnimalHospitalApi() {
        // DB 등 관련 없는 인프라를 실행하지 않도록 클라이언트를 직접 생성합니다.
        AnimalHospitalApiProperties properties =
                new AnimalHospitalApiProperties(
                        BASE_URL,
                        System.getenv("PUBLIC_DATA_SERVICE_KEY"),
                        100,
                        List.of("3130000")
                );
        AnimalHospitalPublicDataClient client =
                new AnimalHospitalPublicDataClient(properties);

        // 마포구 개방자치단체 코드로 첫 번째 페이지에서 최대 10건만 요청합니다.
        AnimalHospitalApiResponse response = client.fetch(
                1,
                10,
                "3130000"
        );

        // 실제 JSON이 응답 DTO의 각 계층으로 정상 변환됐는지 확인합니다.
        assertThat(response).isNotNull();
        assertThat(response.response()).isNotNull();
        assertThat(response.response().header().resultCode()).isEqualTo("0");
        assertThat(response.response().body()).isNotNull();
        assertThat(response.response().body().items().item())
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(10);
    }
}
