package com.doctorpet.domain.hospital.publicdata.infrastructure;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.net.http.HttpClient;

/**
 * 동물병원 OpenAPI를 호출하고 변환된 응답 DTO를 반환합니다.
 */
@Component
@Slf4j
public class AnimalHospitalPublicDataClient {

    private final AnimalHospitalApiProperties properties;
    private final RestClient restClient;

    /**
     * 설정된 OpenAPI 기본 주소로 재사용할 HTTP 클라이언트를 생성합니다.
     */
    public AnimalHospitalPublicDataClient(AnimalHospitalApiProperties properties) {
        this.properties = properties;

        // 외부 API 장애가 애플리케이션 시작을 무기한 지연시키지 않도록 타임아웃을 적용합니다.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
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
        try {
            return restClient.get()
                    .uri(uriBuilder -> buildUri(
                            uriBuilder,
                            pageNo,
                            numOfRows,
                            localGovernmentCode
                    ))
                    .retrieve()
                    .body(AnimalHospitalApiResponse.class);
        } catch (RestClientException exception) {
            // 인증키가 포함된 요청 URI가 예외 로그에 노출되지 않도록 안전한 정보만 기록합니다.
            log.warn(
                    "동물병원 공공데이터 API 호출 실패: pageNo={}, localGovernmentCode={}, errorType={}",
                    pageNo,
                    localGovernmentCode,
                    exception.getClass().getSimpleName()
            );
            throw new IllegalStateException(
                    "동물병원 공공데이터 API 호출에 실패했습니다."
            );
        }
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
