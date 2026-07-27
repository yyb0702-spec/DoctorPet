package com.doctorpet.domain.hospital.partnership.dto;

/**
 * 공공데이터 병원과 매칭할 제휴 병원 더미 데이터를 표현합니다.
 */
public record PartnerHospitalSeedData(
        String localGovernmentCode,
        String managementNumber,
        String hospitalName
) {
}
