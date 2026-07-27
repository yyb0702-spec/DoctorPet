package com.doctorpet.domain.hospital.publicdata.model;

/**
 * 개별 지자체의 페이지 수와 병원 적재 건수를 요약합니다.
 */
public record AnimalHospitalRegionCollectionResult(
        int importedPageCount,
        int importedHospitalCount
) {
}
