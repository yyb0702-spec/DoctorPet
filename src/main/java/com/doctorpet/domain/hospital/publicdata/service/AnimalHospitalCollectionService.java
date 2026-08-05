package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalBody;
import com.doctorpet.domain.hospital.publicdata.infrastructure.AnimalHospitalPublicDataClient;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 전국 동물병원 공공데이터의 모든 페이지를 수집해 적재합니다. */
@Service
@RequiredArgsConstructor
public class AnimalHospitalCollectionService {

    private final AnimalHospitalApiProperties properties;
    private final AnimalHospitalPublicDataClient client;
    private final AnimalHospitalPageImportService pageImportService;

    /** 지역 조건 없이 전국 동물병원 공공데이터를 수집합니다. */
    public AnimalHospitalCollectionResult collectNationwide() {
        validateProperties();

        return collectNationwidePages();
    }

    private AnimalHospitalCollectionResult collectNationwidePages() {
        int pageNo = 1;
        int importedPageCount = 0;
        int importedHospitalCount = 0;

        while (true) {
            // 외부 API 호출은 DB 저장 트랜잭션 밖에서 수행합니다.
            AnimalHospitalApiResponse response = client.fetch(
                    pageNo,
                    properties.pageSize()
            );
            AnimalHospitalBody body = requireSuccessfulBody(response);

            importedHospitalCount += pageImportService.importPage(response);
            importedPageCount++;

            if (pageNo * properties.pageSize() >= body.totalCount()) {
                break;
            }
            pageNo++;
        }

        return new AnimalHospitalCollectionResult(
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
        if (!StringUtils.hasText(properties.serviceKey())) {
            throw new IllegalStateException(
                    "공공데이터 인증키가 필요합니다."
            );
        }
        if (properties.pageSize() <= 0) {
            throw new IllegalStateException(
                    "공공데이터 페이지 크기는 1 이상이어야 합니다."
            );
        }
    }
}
