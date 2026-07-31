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

    private AiConsultationService service;

    @BeforeEach
    void setUp() {
        service = new AiConsultationService(
                aiGateway,
                repository,
                new SymptomTextMasker(),
                hospitalService
        );
    }

    @Test
    @DisplayName("성공 결과를 구조화해 반환하고 개인정보가 마스킹된 상담 로그를 저장한다")
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
                new AiConsultationRequest("010-1234-5678 강아지가 밥을 안 먹어요", PetSpecies.DOG, "서울")
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        AiConsultation saved = captor.getValue();
        assertThat(response.fallback()).isFalse();
        assertThat(response.structured().requiredCapabilities()).containsExactly("BLOOD_TEST");
        assertThat(response.hospitals()).containsExactly(hospital);
        assertThat(response.disclaimer()).isNotBlank();
        assertThat(saved.getMemberId()).isEqualTo(1L);
        assertThat(saved.getSymptomText()).isEqualTo("[MASKED] 강아지가 밥을 안 먹어요");
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
                new AiConsultationRequest("고양이가 기침해요", PetSpecies.CAT, null)
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
                new AiConsultationRequest("다리를 절어요", PetSpecies.DOG, null)
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
                new AiConsultationRequest("다리를 절어요", PetSpecies.DOG, "인천")
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
