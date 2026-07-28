package com.doctorpet.domain.hospital.publicdata.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAPI의 item 배열을 감싸는 객체를 표현합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnimalHospitalItems(
        List<AnimalHospitalItem> item
) {
}
