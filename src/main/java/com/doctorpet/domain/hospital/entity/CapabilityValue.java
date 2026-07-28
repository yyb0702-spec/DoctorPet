package com.doctorpet.domain.hospital.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 병원 검색과 AI 추천이 공통으로 사용하는 진료 역량 화이트리스트입니다.
 */
@Getter
@RequiredArgsConstructor
public enum CapabilityValue {

    DOG(CapabilityType.SPECIES),
    CAT(CapabilityType.SPECIES),

    BLOOD_TEST(CapabilityType.EXAM),
    XRAY(CapabilityType.EXAM),
    ULTRASOUND(CapabilityType.EXAM),

    ORTHOPEDIC_CARE(CapabilityType.TREATMENT),
    DENTAL_CARE(CapabilityType.TREATMENT),
    OPHTHALMIC_CARE(CapabilityType.TREATMENT),
    REHABILITATION(CapabilityType.TREATMENT),
    ONCOLOGY_CARE(CapabilityType.TREATMENT),

    CT(CapabilityType.EQUIPMENT),
    MRI(CapabilityType.EQUIPMENT),
    ENDOSCOPE(CapabilityType.EQUIPMENT);

    private final CapabilityType type;
}
