package com.doctorpet.domain.hospital.publicdata.infrastructure;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.net.URI;

/**
 * 동물병원 OpenAPI를 호출하고 변환된 응답 DTO를 반환합니다.
 */
@Component
public class AnimalHospitalPublicDataClient {

    private final AnimalHospitalApiProperties properties;
    private final RestClient restClient;

    /**
     * 설정된 OpenAPI 기본 주소로 재사용할 HTTP 클라이언트를 생성합니다.
     */
    public AnimalHospitalPublicDataClient(AnimalHospitalApiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    /**
     * 지정한 지자체의 동물병원 데이터를 페이지 단위로 조회합니다.
     * 지자체 코드가 없으면 향후 전국 적재에서 사용할 수 있도록 지역 조건을 생략합니다.
     */
    public AnimalHospitalApiResponse fetch(
            int pageNo,
            int numOfRows,
            String localGovernmentCode
    ) {
        return restClient.get()
                .uri(uriBuilder -> buildUri(
                        uriBuilder,
                        pageNo,
                        numOfRows,
                        localGovernmentCode
                ))
                .retrieve()
                .body(AnimalHospitalApiResponse.class);
    }

    private URI buildUri(
            UriBuilder uriBuilder,
            int pageNo,
            int numOfRows,
            String localGovernmentCode
    ) {
        uriBuilder.path("/info")
                .queryParam("serviceKey", properties.serviceKey())
                .queryParam("pageNo", pageNo)
                .queryParam("numOfRows", numOfRows)
                .queryParam("returnType", "json");

        // MVP에서는 개방자치단체 코드가 일치하는 병원만 공공데이터에서 조회합니다.
        if (StringUtils.hasText(localGovernmentCode)) {
            uriBuilder.queryParam(
                    "cond[OPN_ATMY_GRP_CD::EQ]",
                    localGovernmentCode
            );
        }

        return uriBuilder.build();
    }
}
