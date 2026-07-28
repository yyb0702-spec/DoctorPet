package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OpenAPI 응답 한 페이지에 포함된 동물병원 목록을 순서대로 적재합니다.
 */
@Service
@RequiredArgsConstructor
public class AnimalHospitalPageImportService {

    private final AnimalHospitalImportService importService;

    /**
     * 한 페이지의 병원을 각각 독립된 단건 적재 트랜잭션으로 처리하고 처리 건수를 반환합니다.
     */
    public int importPage(AnimalHospitalApiResponse apiResponse) {
        List<AnimalHospitalItem> items = extractItems(apiResponse);

        items.forEach(importService::importHospital);

        return items.size();
    }

    private List<AnimalHospitalItem> extractItems(
            AnimalHospitalApiResponse apiResponse
    ) {
        // 페이지 결과가 없는 경우 빈 목록으로 처리해 불필요한 예외를 방지합니다.
        if (apiResponse == null
                || apiResponse.response() == null
                || apiResponse.response().body() == null
                || apiResponse.response().body().items() == null
                || apiResponse.response().body().items().item() == null) {
            return List.of();
        }

        return apiResponse.response().body().items().item();
    }
}
