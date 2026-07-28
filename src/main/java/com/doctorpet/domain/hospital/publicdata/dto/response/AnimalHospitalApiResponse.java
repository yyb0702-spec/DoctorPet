package com.doctorpet.domain.hospital.publicdata.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 동물병원 OpenAPI의 최상위 응답을 표현합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnimalHospitalApiResponse(
        AnimalHospitalResponse response
) {
}
