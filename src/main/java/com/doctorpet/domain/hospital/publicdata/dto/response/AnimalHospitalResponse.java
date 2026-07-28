package com.doctorpet.domain.hospital.publicdata.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * OpenAPI 응답의 본문과 처리 결과를 묶어서 표현합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnimalHospitalResponse(
        AnimalHospitalBody body,
        AnimalHospitalHeader header
) {
}
