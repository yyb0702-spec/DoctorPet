package com.doctorpet.domain.hospital.publicdata.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 병원 목록과 페이지 정보를 포함한 응답 본문을 표현합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnimalHospitalBody(
        String dataType,
        AnimalHospitalItems items,
        int numOfRows,
        int pageNo,
        int totalCount
) {
}
