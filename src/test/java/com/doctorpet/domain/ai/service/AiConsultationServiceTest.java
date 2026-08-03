package com.doctorpet.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.ai.dto.request.AiConsultationRequest;
import com.doctorpet.domain.ai.dto.response.AiConsultationResponse;
import com.doctorpet.domain.ai.entity.AiConsultation;
import com.doctorpet.domain.ai.entity.AiConsultationStatus;
import com.doctorpet.domain.ai.entity.AiToolCallStatus;
import com.doctorpet.domain.ai.repository.AiConsultationRepository;
import com.doctorpet.domain.ai.support.AiHospitalSearchIntentExtractor;
import com.doctorpet.domain.ai.support.EmergencyKeywordDetector;
import com.doctorpet.domain.ai.support.SymptomTextMasker;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.global.gateway.ai.AiGateway;
import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiConsultationServiceTest {

    @Mock
    private AiGateway aiGateway;

    @Mock
    private AiConsultationRepository repository;

    @Mock
    private HospitalService hospitalService;

    @Mock
    private EmergencyKeywordDetector emergencyKeywordDetector;

    private AiConsultationService service;

    @BeforeEach
    void setUp() {
        service = new AiConsultationService(
                aiGateway,
                repository,
                new SymptomTextMasker(),
                hospitalService,
                new AiHospitalSearchIntentExtractor(),
                emergencyKeywordDetector
        );
    }

    @Test
    @DisplayName("개인정보를 마스킹해 Gateway에 전달하고 상담 로그를 저장한다")
    void consult_success() {
        AiAnalysisResult result = result(List.of("BLOOD_TEST"));
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        HospitalSearchResponse hospital = hospital();
        given(hospitalService.hospitalSearch(
                null,
                "서울",
                null,
                null,
                null,
                List.of("BLOOD_TEST"),
                List.of("DOG"),
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L,
                request("010-1234-5678 강아지가 밥을 안 먹어요", PetSpecies.DOG, "서울")
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        ArgumentCaptor<AiAnalysisRequest> gatewayRequestCaptor =
                ArgumentCaptor.forClass(AiAnalysisRequest.class);
        verify(aiGateway).analyze(gatewayRequestCaptor.capture());
        AiConsultation saved = captor.getValue();
        AiAnalysisRequest gatewayRequest = gatewayRequestCaptor.getValue();
        assertThat(response.fallback()).isFalse();
        assertThat(response.structured().requiredCapabilities()).containsExactly("BLOOD_TEST");
        assertThat(response.hospitals()).containsExactly(hospital);
        assertThat(response.disclaimer()).isNotBlank();
        assertThat(saved.getMemberId()).isEqualTo(1L);
        assertThat(saved.getSymptomText()).isEqualTo("[MASKED] 강아지가 밥을 안 먹어요");
        assertThat(gatewayRequest.symptomText()).isEqualTo("[MASKED] 강아지가 밥을 안 먹어요");
        assertThat(saved.getStatus()).isEqualTo(AiConsultationStatus.SUCCESS);
        assertThat(saved.getRequiredCapabilities()).containsExactly("BLOOD_TEST");
        assertThat(saved.getToolCallStatus()).isEqualTo(AiToolCallStatus.SUCCESS);
        assertThat(saved.isSchemaParseSuccess()).isTrue();
    }

    @Test
    @DisplayName("게이트웨이 timeout은 예외를 전파하지 않고 fallback과 실패 원인을 저장한다")
    void consult_gatewayFailure_returnsFallback() {
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willThrow(
                new AiGatewayException(AiGatewayFailureReason.TIMEOUT, "timeout"));

        AiConsultationResponse response = service.consult(
                null,
                request("고양이가 기침해요", PetSpecies.CAT, null)
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        AiConsultation saved = captor.getValue();
        assertThat(response.fallback()).isTrue();
        assertThat(response.structured()).isNull();
        assertThat(saved.getMemberId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(AiConsultationStatus.FAILED);
        assertThat(saved.getErrorType()).isEqualTo(AiGatewayFailureReason.TIMEOUT);
        assertThat(saved.isFallbackUsed()).isTrue();
        assertThat(saved.getToolCallStatus()).isEqualTo(AiToolCallStatus.NOT_CALLED);
        verify(hospitalService, never()).hospitalSearch(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Boolean.class), any(Boolean.class),
                any(Integer.class), any(Integer.class), any()
        );
    }

    @Test
    @DisplayName("화이트리스트 밖 진료역량은 INVALID_RESPONSE로 기록하고 fallback한다")
    void consult_invalidCapability_returnsFallback() {
        given(aiGateway.analyze(any(AiAnalysisRequest.class)))
                .willReturn(result(List.of("UNKNOWN_CAPABILITY")));

        AiConsultationResponse response = service.consult(
                1L,
                request("다리를 절어요", PetSpecies.DOG, null)
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        assertThat(response.fallback()).isTrue();
        assertThat(captor.getValue().getErrorType())
                .isEqualTo(AiGatewayFailureReason.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("병원 검색 실패는 구조화 결과를 노출하지 않고 fallback과 Tool 실패를 저장한다")
    void consult_hospitalSearchFailure_returnsFallback() {
        AiAnalysisResult result = result(List.of("XRAY"));
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        given(hospitalService.hospitalSearch(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Boolean.class), any(Boolean.class),
                any(Integer.class), any(Integer.class), any()
        )).willThrow(new IllegalStateException("search failed"));

        AiConsultationResponse response = service.consult(
                1L,
                request("다리를 절어요", PetSpecies.DOG, "인천")
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        AiConsultation saved = captor.getValue();
        assertThat(response.fallback()).isTrue();
        assertThat(response.structured()).isNull();
        assertThat(response.hospitals()).isEmpty();
        assertThat(saved.getStatus()).isEqualTo(AiConsultationStatus.FAILED);
        assertThat(saved.getErrorType()).isNull();
        assertThat(saved.getToolCallStatus()).isEqualTo(AiToolCallStatus.FAILED);
        assertThat(saved.isSchemaParseSuccess()).isTrue();
        assertThat(saved.getRequiredCapabilities()).containsExactly("XRAY");
    }

    @Test
    @DisplayName("위치와 현재·야간·거리 의도를 병원 검색 조건으로 전달한다")
    void consult_searchIntent_passesAllowedConditions() {
        AiAnalysisResult result = result(List.of("BLOOD_TEST"));
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        BigDecimal latitude = new BigDecimal("37.5665");
        BigDecimal longitude = new BigDecimal("126.9780");
        given(hospitalService.hospitalSearch(
                null, null, latitude, longitude, null,
                List.of("BLOOD_TEST"), List.of("DOG"),
                null, null, true, null, false, true, 1, 20, "distance"
        )).willReturn(HospitalSearchPageResponse.of(List.of(), 1, 20, 0, 0));

        service.consult(1L, new AiConsultationRequest(
                "지금 진료 가능한 가까운 24시간 병원을 찾아줘",
                PetSpecies.DOG,
                null,
                latitude,
                longitude
        ));

        verify(hospitalService).hospitalSearch(
                null, null, latitude, longitude, null,
                List.of("BLOOD_TEST"), List.of("DOG"),
                null, null, true, null, false, true, 1, 20, "distance"
        );
    }

    @Test
    @DisplayName("새벽인데 지금 진료 가능한 요청은 야간 조건 없이 현재 영업만 적용한다")
    void consult_currentAvailabilityAtDawn_doesNotForceNightCare() {
        AiAnalysisResult result = result(List.of());
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        given(hospitalService.hospitalSearch(
                null, "서울", null, null, null,
                List.of(), List.of("CAT"), null, null, null, null,
                false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(), 1, 20, 0, 0));

        service.consult(null, request("새벽인데 지금 진료 가능한 병원", PetSpecies.CAT, "서울"));

        verify(hospitalService).hospitalSearch(
                null, "서울", null, null, null,
                List.of(), List.of("CAT"), null, null, null, null,
                false, true, 1, 20, "name"
        );
    }

    @Test
    @DisplayName("HIGH 긴급도는 응급과 현재 영업 조건으로 강제한다")
    void consult_highUrgency_forcesEmergencySearch() {
        AiAnalysisResult result = new AiAnalysisResult(
                List.of(), List.of(), UrgencyLevel.HIGH, List.of(), true,
                null, null, null, null);
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        given(hospitalService.hospitalSearch(
                null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L, request("상태가 갑자기 나빠졌어요", PetSpecies.DOG, "서울"));

        assertThat(response.message()).contains("입력한 지역");
        assertThat(response.locationRecommended()).isTrue();
        verify(hospitalService).hospitalSearch(
                null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "name"
        );
    }

    @Test
    @DisplayName("응급 키워드는 AI를 호출하지 않고 응급 병원을 검색한다")
    void consult_emergencyKeyword_bypassesGateway() {
        given(emergencyKeywordDetector.isEmergency("강아지가 호흡곤란을 보여요")).willReturn(true);
        given(hospitalService.hospitalSearch(
                null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L, request("강아지가 호흡곤란을 보여요", PetSpecies.DOG, "서울"));

        assertThat(response.structured().urgencyLevel()).isEqualTo(UrgencyLevel.HIGH);
        assertThat(response.message()).contains("입력한 지역");
        assertThat(response.locationRecommended()).isTrue();
        verify(aiGateway, never()).analyze(any());
    }

    @Test
    @DisplayName("HIGH 긴급도에 좌표가 있으면 거리 표현 없이 거리순을 강제한다")
    void consult_highUrgencyWithLocation_forcesDistanceSort() {
        AiAnalysisResult result = new AiAnalysisResult(
                List.of(), List.of(), UrgencyLevel.HIGH, List.of(), true,
                null, null, null, null);
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        BigDecimal latitude = new BigDecimal("37.5665");
        BigDecimal longitude = new BigDecimal("126.9780");
        given(hospitalService.hospitalSearch(
                null, null, latitude, longitude, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "distance"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L,
                new AiConsultationRequest(
                        "상태가 갑자기 나빠졌어요",
                        PetSpecies.DOG,
                        null,
                        latitude,
                        longitude
                )
        );

        assertThat(response.locationRecommended()).isFalse();
        assertThat(response.message()).contains("가까운 순");
        verify(hospitalService).hospitalSearch(
                null, null, latitude, longitude, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "distance"
        );
    }

    @Test
    @DisplayName("응급 병원 검색 실패 시 병원 목록을 안내했다고 표현하지 않는다")
    void consult_emergencyKeywordSearchFailure_returnsEmergencyFallbackMessage() {
        BigDecimal latitude = new BigDecimal("37.5665");
        BigDecimal longitude = new BigDecimal("126.9780");
        given(emergencyKeywordDetector.isEmergency("강아지가 호흡곤란을 보여요"))
                .willReturn(true);
        given(hospitalService.hospitalSearch(
                null, null, latitude, longitude, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "distance"
        )).willThrow(new IllegalStateException("search failed"));

        AiConsultationResponse response = service.consult(
                1L,
                new AiConsultationRequest(
                        "강아지가 호흡곤란을 보여요",
                        PetSpecies.DOG,
                        null,
                        latitude,
                        longitude
                )
        );

        assertThat(response.hospitals()).isEmpty();
        assertThat(response.message()).contains("병원 정보를 불러오지 못했습니다");
        assertThat(response.message()).doesNotContain("가까운 순으로 안내");
        assertThat(response.fallback()).isTrue();
        assertThat(response.locationRecommended()).isFalse();
    }

    @Test
    @DisplayName("응급 병원 검색 결과가 없으면 병원을 안내했다고 표현하지 않는다")
    void consult_highUrgencyWithoutSearchResult_returnsNotFoundMessage() {
        AiAnalysisResult result = new AiAnalysisResult(
                List.of(), List.of(), UrgencyLevel.HIGH, List.of(), true,
                null, null, null, null);
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        given(hospitalService.hospitalSearch(
                null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(), 1, 20, 0, 0));

        AiConsultationResponse response = service.consult(
                1L, request("상태가 갑자기 나빠졌어요", PetSpecies.DOG, "서울"));

        assertThat(response.hospitals()).isEmpty();
        assertThat(response.message()).contains("병원을 찾지 못했습니다");
        assertThat(response.message()).doesNotContain("입력한 지역에서");
        assertThat(response.fallback()).isFalse();
        assertThat(response.locationRecommended()).isTrue();
    }

    @Test
    @DisplayName("HIGH 긴급도에 좌표와 지역이 없으면 검색 없이 위치 제공을 권장한다")
    void consult_highUrgencyWithoutLocation_recommendsLocation() {
        AiAnalysisResult result = new AiAnalysisResult(
                List.of(), List.of(), UrgencyLevel.HIGH, List.of(), true,
                null, null, null, null);
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);

        AiConsultationResponse response = service.consult(
                1L,
                request("상태가 갑자기 나빠졌어요", PetSpecies.DOG, null)
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        assertThat(response.message()).contains("현재 위치를 제공하거나 지역을 입력");
        assertThat(response.locationRecommended()).isTrue();
        assertThat(response.fallback()).isFalse();
        assertThat(captor.getValue().getToolCallStatus())
                .isEqualTo(AiToolCallStatus.NOT_CALLED);
        verify(hospitalService, never()).hospitalSearch(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Boolean.class), any(Boolean.class),
                any(Integer.class), any(Integer.class), any()
        );
    }

    @Test
    @DisplayName("응급 키워드에 좌표와 지역이 없으면 검색 없이 위치 제공을 권장한다")
    void consult_emergencyKeywordWithoutLocation_recommendsLocation() {
        given(emergencyKeywordDetector.isEmergency("강아지가 호흡곤란을 보여요"))
                .willReturn(true);

        AiConsultationResponse response = service.consult(
                1L,
                request("강아지가 호흡곤란을 보여요", PetSpecies.DOG, null)
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        assertThat(response.message()).contains("현재 위치를 제공하거나 지역을 입력");
        assertThat(response.locationRecommended()).isTrue();
        assertThat(response.fallback()).isFalse();
        assertThat(captor.getValue().getToolCallStatus())
                .isEqualTo(AiToolCallStatus.NOT_CALLED);
        verify(aiGateway, never()).analyze(any());
        verify(hospitalService, never()).hospitalSearch(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Boolean.class), any(Boolean.class),
                any(Integer.class), any(Integer.class), any()
        );
    }

    @Test
    @DisplayName("거리 요청에 좌표와 지역이 없으면 검색하지 않고 위치 입력을 안내한다")
    void consult_distanceWithoutLocation_skipsTool() {
        AiAnalysisResult result = result(List.of());
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);

        AiConsultationResponse response = service.consult(
                1L, request("가장 가까운 병원을 찾아줘", PetSpecies.DOG, null));

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        assertThat(response.hospitals()).isEmpty();
        assertThat(response.message()).contains("위치");
        assertThat(response.fallback()).isFalse();
        assertThat(captor.getValue().getToolCallStatus()).isEqualTo(AiToolCallStatus.NOT_CALLED);
        verify(hospitalService, never()).hospitalSearch(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Boolean.class), any(Boolean.class),
                any(Integer.class), any(Integer.class), any()
        );
    }

    private AiConsultationRequest request(
            String symptomText,
            PetSpecies species,
            String region
    ) {
        return new AiConsultationRequest(symptomText, species, region, null, null);
    }

    private AiAnalysisResult result(List<String> capabilities) {
        return new AiAnalysisResult(
                List.of("GENERAL"),
                capabilities,
                UrgencyLevel.MODERATE,
                List.of("증상 시작 시점을 확인해 주세요."),
                true,
                null,
                null,
                null,
                null
        );
    }

    private HospitalSearchResponse hospital() {
        return new HospitalSearchResponse(
                10L,
                "서울동물병원",
                "서울특별시 중구",
                null,
                BusinessStatus.OPEN,
                PartnershipStatus.PARTNER,
                true,
                null,
                true
        );
    }
}
