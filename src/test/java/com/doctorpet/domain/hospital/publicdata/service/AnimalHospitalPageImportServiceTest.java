package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalBody;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalHeader;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItem;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItems;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalPageImportServiceTest {

    @Mock
    private AnimalHospitalImportService importService;

    private AnimalHospitalPageImportService pageImportService;

    @BeforeEach
    void setUp() {
        pageImportService = new AnimalHospitalPageImportService(importService);
    }

    @Test
    void 한_페이지의_모든_병원을_단건_적재한다() {
        AnimalHospitalItem first = createItem(
                "313000001020260004",
                "시그널 동물의료센터"
        );
        AnimalHospitalItem second = createItem(
                "407000001020260002",
                "경기남부동물병원"
        );
        AnimalHospitalApiResponse response = createResponse(
                List.of(first, second)
        );

        int importedCount = pageImportService.importPage(response);

        // 응답의 각 병원이 단건 적재 서비스에 한 번씩 전달되는지 확인합니다.
        assertThat(importedCount).isEqualTo(2);
        then(importService).should().importHospital(first);
        then(importService).should().importHospital(second);
    }

    @Test
    void 병원_목록이_없으면_아무것도_적재하지_않는다() {
        AnimalHospitalApiResponse response = createResponse(List.of());

        int importedCount = pageImportService.importPage(response);

        // 조회 결과가 없는 마지막 페이지도 정상적으로 종료되는지 확인합니다.
        assertThat(importedCount).isZero();
        then(importService).should(never()).importHospital(
                org.mockito.ArgumentMatchers.any()
        );
    }

    private AnimalHospitalApiResponse createResponse(
            List<AnimalHospitalItem> items
    ) {
        return new AnimalHospitalApiResponse(
                new AnimalHospitalResponse(
                        new AnimalHospitalBody(
                                "JSON",
                                new AnimalHospitalItems(items),
                                10,
                                1,
                                items.size()
                        ),
                        new AnimalHospitalHeader("0", "정상")
                )
        );
    }

    private AnimalHospitalItem createItem(
            String managementNumber,
            String name
    ) {
        return new AnimalHospitalItem(
                name,
                "",
                "",
                "",
                "2026-07-25 22:05:05",
                "I",
                "0000",
                "정상",
                "2026-07-24 15:52:02",
                "",
                "2026-07-24",
                "",
                "",
                "서울특별시 마포구 성산동",
                managementNumber,
                "3130000",
                "000",
                "서울특별시 마포구 모래내로1길 9",
                "03938",
                "",
                "01",
                "영업/정상",
                "",
                "",
                ""
        );
    }
}
