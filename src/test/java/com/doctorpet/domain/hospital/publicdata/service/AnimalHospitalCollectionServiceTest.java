package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalBody;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalHeader;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItems;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalResponse;
import com.doctorpet.domain.hospital.publicdata.infrastructure.AnimalHospitalPublicDataClient;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalCollectionServiceTest {

    @Mock
    private AnimalHospitalPublicDataClient client;

    @Mock
    private AnimalHospitalPageImportService pageImportService;

    private AnimalHospitalCollectionService collectionService;

    @BeforeEach
    void setUp() {
        AnimalHospitalApiProperties properties =
                new AnimalHospitalApiProperties(
                        "https://example.com",
                        "test-key",
                        2,
                        List.of("3130000")
                );
        collectionService = new AnimalHospitalCollectionService(
                properties,
                client,
                pageImportService
        );
    }

    @Test
    void 설정된_지역의_마지막_페이지까지_수집한다() {
        AnimalHospitalApiResponse firstPage = createResponse(1, 5);
        AnimalHospitalApiResponse secondPage = createResponse(2, 5);
        AnimalHospitalApiResponse thirdPage = createResponse(3, 5);
        given(client.fetch(1, 2, "3130000")).willReturn(firstPage);
        given(client.fetch(2, 2, "3130000")).willReturn(secondPage);
        given(client.fetch(3, 2, "3130000")).willReturn(thirdPage);
        given(pageImportService.importPage(firstPage)).willReturn(2);
        given(pageImportService.importPage(secondPage)).willReturn(2);
        given(pageImportService.importPage(thirdPage)).willReturn(1);

        AnimalHospitalCollectionResult result =
                collectionService.collectConfiguredRegions();

        // totalCount와 페이지 크기로 계산한 마지막 페이지까지만 호출하는지 확인합니다.
        assertThat(result.collectedRegionCount()).isEqualTo(1);
        assertThat(result.importedPageCount()).isEqualTo(3);
        assertThat(result.importedHospitalCount()).isEqualTo(5);
        then(client).should().fetch(1, 2, "3130000");
        then(client).should().fetch(2, 2, "3130000");
        then(client).should().fetch(3, 2, "3130000");
    }

    private AnimalHospitalApiResponse createResponse(
            int pageNo,
            int totalCount
    ) {
        return new AnimalHospitalApiResponse(
                new AnimalHospitalResponse(
                        new AnimalHospitalBody(
                                "JSON",
                                new AnimalHospitalItems(List.of()),
                                2,
                                pageNo,
                                totalCount
                        ),
                        new AnimalHospitalHeader("0", "정상")
                )
        );
    }
}
