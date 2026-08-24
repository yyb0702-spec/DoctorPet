package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalApiResponse;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalBody;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalHeader;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItems;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalResponse;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.global.gateway.publicdata.PublicDataGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AnimalHospitalCollectionServiceTest {

    @Mock
    private PublicDataGateway publicDataGateway;

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
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(10),
                        "EPSG:5174",
                        3,
                        Duration.ofMinutes(5)
                );
        collectionService = new AnimalHospitalCollectionService(
                properties,
                publicDataGateway,
                pageImportService
        );
    }

    @Test
    void 전국_데이터의_마지막_페이지까지_수집한다() {
        AnimalHospitalApiResponse firstPage = createResponse(1, 5);
        AnimalHospitalApiResponse secondPage = createResponse(2, 5);
        AnimalHospitalApiResponse thirdPage = createResponse(3, 5);
        given(publicDataGateway.fetch(1, 2)).willReturn(firstPage);
        given(publicDataGateway.fetch(2, 2)).willReturn(secondPage);
        given(publicDataGateway.fetch(3, 2)).willReturn(thirdPage);
        given(pageImportService.importPage(firstPage)).willReturn(2);
        given(pageImportService.importPage(secondPage)).willReturn(2);
        given(pageImportService.importPage(thirdPage)).willReturn(1);

        AnimalHospitalCollectionResult result =
                collectionService.collectNationwide();

        // totalCount와 페이지 크기로 계산한 마지막 페이지까지만 호출하는지 확인합니다.
        assertThat(result.importedPageCount()).isEqualTo(3);
        assertThat(result.importedHospitalCount()).isEqualTo(5);
        then(publicDataGateway).should().fetch(1, 2);
        then(publicDataGateway).should().fetch(2, 2);
        then(publicDataGateway).should().fetch(3, 2);
    }

    @Test
    void 수집에_실패하면_실패_페이지와_부분_반영_건수를_기록한다(
            CapturedOutput output
    ) {
        AnimalHospitalApiResponse firstPage = createResponse(1, 5);
        given(publicDataGateway.fetch(1, 2)).willReturn(firstPage);
        given(pageImportService.importPage(firstPage)).willReturn(2);
        given(publicDataGateway.fetch(2, 2))
                .willThrow(new IllegalStateException("temporary failure"));

        assertThatThrownBy(collectionService::collectNationwide)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("temporary failure");

        assertThat(output)
                .contains("failedPage=2")
                .contains("importedPages=1")
                .contains("importedHospitals=2");
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
