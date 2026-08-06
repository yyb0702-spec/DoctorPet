package com.doctorpet.domain.hospital.publicdata.model;

/** 공공데이터 수집과 제휴 병원 보강을 포함한 전체 갱신 결과입니다. */
public record AnimalHospitalRefreshResult(
        AnimalHospitalCollectionResult collection,
        int appliedPartnerCount
) {
}
