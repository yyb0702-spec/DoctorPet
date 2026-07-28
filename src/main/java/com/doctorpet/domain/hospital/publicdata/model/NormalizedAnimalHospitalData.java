package com.doctorpet.domain.hospital.publicdata.model;

import com.doctorpet.domain.hospital.entity.BusinessStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 공공데이터 원본을 애플리케이션에서 사용할 타입으로 정규화한 결과입니다.
 */
public record NormalizedAnimalHospitalData(
        String managementNumber,
        String localGovernmentCode,
        String name,
        String phone,
        String jibunAddress,
        String roadAddress,
        String zipcode,
        BigDecimal coordinateX,
        BigDecimal coordinateY,
        LocalDate licenseDate,
        BusinessStatus businessStatus,
        LocalDate closureDate,
        BigDecimal area,
        LocalDateTime sourceModifiedAt
) {
}
