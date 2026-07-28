package com.doctorpet.domain.hospital.publicdata.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OpenAPI 호출 결과 코드와 메시지를 표현합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnimalHospitalHeader(
        String resultCode,
        String resultMsg
) {
}
