package com.doctorpet.domain.hospital.publicdata.model;

/**
 * 설정된 지역의 공공데이터 수집 결과를 요약합니다.
 */
public record AnimalHospitalCollectionResult(
        int collectedRegionCount,
        int importedPageCount,
        int importedHospitalCount
) {
}
