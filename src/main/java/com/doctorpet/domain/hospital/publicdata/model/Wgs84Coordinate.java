package com.doctorpet.domain.hospital.publicdata.model;

import java.math.BigDecimal;

/**
 * 거리 검색에 사용하는 WGS84 경도와 위도를 표현합니다.
 */
public record Wgs84Coordinate(
        BigDecimal longitude,
        BigDecimal latitude
) {
}
