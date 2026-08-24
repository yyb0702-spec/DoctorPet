package com.doctorpet.domain.hospital.publicdata.model;

/** 전국 동물병원 공공데이터 수집 결과를 요약합니다. */
public record AnimalHospitalCollectionResult(
        int importedPageCount,
        int importedHospitalCount
) {
}
