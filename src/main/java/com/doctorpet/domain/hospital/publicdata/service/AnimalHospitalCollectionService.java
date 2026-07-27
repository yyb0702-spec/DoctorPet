package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalBody;
import com.doctorpet.domain.hospital.publicdata.infrastructure.AnimalHospitalPublicDataClient;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRegionCollectionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 설정된 지자체별로 OpenAPI의 모든 페이지를 호출해 병원 데이터를 적재합니다.
 */
@Service
@RequiredArgsConstructor
public class AnimalHospitalCollectionService {

    private final AnimalHospitalApiProperties properties;
    private final AnimalHospitalPublicDataClient client;
    private final AnimalHospitalPageImportService pageImportService;

    /**
     * YAML에 설정된 지자체들의 전체 페이지를 순차적으로 수집합니다.
     */
    public AnimalHospitalCollectionResult collectConfiguredRegions() {
        validateProperties();

        int importedPageCount = 0;
        int importedHospitalCount = 0;

        for (String localGovernmentCode : properties.localGovernmentCodes()) {
            AnimalHospitalRegionCollectionResult result =
                    collectRegion(localGovernmentCode);
            importedPageCount += result.importedPageCount();
            importedHospitalCount += result.importedHospitalCount();
        }

        return new AnimalHospitalCollectionResult(
                properties.localGovernmentCodes().size(),
                importedPageCount,
                importedHospitalCount
        );
    }

    private AnimalHospitalRegionCollectionResult collectRegion(
            String localGovernmentCode
    ) {
        int pageNo = 1;
        int importedPageCount = 0;
        int importedHospitalCount = 0;

        while (true) {
            // 외부 API 호출은 DB 저장 트랜잭션 밖에서 수행합니다.
            AnimalHospitalApiResponse response = client.fetch(
                    pageNo,
                    properties.pageSize(),
                    localGovernmentCode
            );
            AnimalHospitalBody body = requireSuccessfulBody(response);

            importedHospitalCount += pageImportService.importPage(response);
            importedPageCount++;

            if (pageNo * properties.pageSize() >= body.totalCount()) {
                break;
            }
            pageNo++;
        }

        return new AnimalHospitalRegionCollectionResult(
                importedPageCount,
                importedHospitalCount
        );
    }

    private AnimalHospitalBody requireSuccessfulBody(
            AnimalHospitalApiResponse response
    ) {
        if (response == null
                || response.response() == null
                || response.response().header() == null
                || !"0".equals(response.response().header().resultCode())) {
            throw new IllegalStateException(
                    "동물병원 공공데이터 API 호출에 실패했습니다."
            );
        }
        if (response.response().body() == null) {
            throw new IllegalStateException(
                    "동물병원 공공데이터 API 응답 본문이 없습니다."
            );
        }
        return response.response().body();
    }

    private void validateProperties() {
        if (properties.pageSize() <= 0) {
            throw new IllegalStateException(
                    "공공데이터 페이지 크기는 1 이상이어야 합니다."
            );
        }
        if (properties.localGovernmentCodes() == null
                || properties.localGovernmentCodes().isEmpty()) {
            throw new IllegalStateException(
                    "적재할 개방자치단체코드가 필요합니다."
            );
        }
    }

}
