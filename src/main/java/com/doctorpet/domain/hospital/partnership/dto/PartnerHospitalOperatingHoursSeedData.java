package com.doctorpet.domain.hospital.partnership.dto;

/**
 * 제휴 병원의 하루 운영 시작·종료 시간을 표현합니다.
 */
public record PartnerHospitalOperatingHoursSeedData(
        String openTime,
        String closeTime
) {
}
