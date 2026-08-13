package com.doctorpet.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.ai.dto.request.AiConsultationRequest;
import com.doctorpet.domain.ai.dto.AiHospitalCandidateEvidence;
import com.doctorpet.domain.ai.dto.response.AiConsultationResponse;
import com.doctorpet.domain.ai.dto.response.AiHospitalRecommendationResponse;
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
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.review.dto.response.HospitalReviewEvidence;
import com.doctorpet.domain.review.dto.response.ReviewExcerpt;
import com.doctorpet.domain.review.model.ReviewRatingBand;
import com.doctorpet.domain.review.service.ReviewQueryService;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.global.gateway.ai.AiGateway;
import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.AiGatewayConsultationResult;
import com.doctorpet.global.gateway.ai.dto.AiHospitalRecommendationResult;
import com.doctorpet.global.gateway.ai.dto.AiRecommendationEvidenceResult;
import com.doctorpet.global.gateway.ai.dto.AiRecommendationEvidenceType;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import com.doctorpet.domain.hospital.model.HospitalSearchSort;
import com.doctorpet.global.gateway.ai.tool.AiHospitalSearchToolCall;
import com.doctorpet.global.gateway.ai.tool.AiToolExecutor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

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

    @Mock
    private ReviewQueryService reviewQueryService;

    private AiConsultationService service;

    @BeforeEach
    void setUp() {
        lenient().when(aiGateway.consult(any(), any())).thenCallRealMethod();
        service = new AiConsultationService(
                aiGateway,
                repository,
                new SymptomTextMasker(),
                hospitalService,
                new AiHospitalSearchIntentExtractor(),
                emergencyKeywordDetector,
                reviewQueryService,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("개인정보를 마스킹해 Gateway에 전달하고 상담 로그를 저장한다")
    void consult_success() {
        AiAnalysisResult result = result(List.of("BLOOD_TEST"));
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        HospitalSearchResponse hospital = hospital();
        given(hospitalService.hospitalSearch(
                1L,
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
        verify(emergencyKeywordDetector)
                .isEmergency("[MASKED] 강아지가 밥을 안 먹어요");
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
    @DisplayName("Tool Calling 응답의 자유 질환명과 처치 지시는 INVALID_RESPONSE로 차단한다")
    void consult_unsafeStructuredStrings_returnsFallback() {
        AiAnalysisResult unsafe = new AiAnalysisResult(
                List.of("골절"),
                List.of("XRAY"),
                UrgencyLevel.MODERATE,
                List.of("진통제를 먹이세요"),
                true,
                "gpt-4.1-mini",
                "doctorpet-ai-v4",
                10,
                5
        );
        doReturn(new AiGatewayConsultationResult(
                unsafe, false, true, false))
                .when(aiGateway).consult(any(), any());

        AiConsultationResponse response = service.consult(
                1L,
                request("다리를 절어요", PetSpecies.DOG, "서울")
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        assertThat(response.fallback()).isTrue();
        assertThat(response.structured()).isNull();
        assertThat(response.message()).doesNotContain("골절", "진통제");
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
    @DisplayName("OpenAI Tool 검색 실패 시 이미 사용한 1차 호출 토큰을 실패 상담에 저장한다")
    void consult_openAiToolSearchFailure_preservesFirstCallUsage() {
        AiAnalysisResult firstAnalysis = new AiAnalysisResult(
                List.of("GENERAL"),
                List.of("XRAY"),
                UrgencyLevel.MODERATE,
                List.of("ONSET_TIME"),
                true,
                "gpt-4.1-mini",
                "doctorpet-ai-v4",
                100,
                20
        );
        doAnswer(invocation -> {
            AiToolExecutor executor = invocation.getArgument(1);
            executor.searchNearbyVets(new AiHospitalSearchToolCall(
                    firstAnalysis,
                    null,
                    null,
                    true,
                    HospitalSearchSort.NAME
            ));
            throw new AssertionError("검색 실패 후 Gateway가 정상 결과를 반환하면 안 됩니다.");
        }).when(aiGateway).consult(any(), any());
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
        assertThat(saved.getToolCallStatus()).isEqualTo(AiToolCallStatus.FAILED);
        assertThat(saved.getPromptTokens()).isEqualTo(100);
        assertThat(saved.getCompletionTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("Tool 성공 후 2차 OpenAI 실패는 1차 사용량과 Tool 성공을 보존한다")
    void consult_secondOpenAiCallFailure_preservesFirstCallUsageAndToolSuccess() {
        AiAnalysisResult firstAnalysis = new AiAnalysisResult(
                List.of("GENERAL"),
                List.of("XRAY"),
                UrgencyLevel.MODERATE,
                List.of("ONSET_TIME"),
                true,
                "gpt-4.1-mini",
                "doctorpet-ai-v8",
                100,
                20
        );
        doAnswer(invocation -> {
            AiToolExecutor executor = invocation.getArgument(1);
            executor.searchNearbyVets(new AiHospitalSearchToolCall(
                    firstAnalysis,
                    null,
                    null,
                    null,
                    HospitalSearchSort.NAME
            ));
            throw new AiGatewayException(
                    AiGatewayFailureReason.TIMEOUT,
                    "second call timeout"
            );
        }).when(aiGateway).consult(any(), any());
        given(hospitalService.hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of("XRAY"), List.of("DOG"), null, null,
                null, null, false, false, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L,
                request("검사 가능한 병원을 알려줘", PetSpecies.DOG, "서울")
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        AiConsultation saved = captor.getValue();
        assertThat(response.fallback()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(AiConsultationStatus.FAILED);
        assertThat(saved.getErrorType()).isEqualTo(AiGatewayFailureReason.TIMEOUT);
        assertThat(saved.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(saved.getPromptVersion()).isEqualTo("doctorpet-ai-v8");
        assertThat(saved.getPromptTokens()).isEqualTo(100);
        assertThat(saved.getCompletionTokens()).isEqualTo(20);
        assertThat(saved.getToolCallStatus()).isEqualTo(AiToolCallStatus.SUCCESS);
        assertThat(saved.isSchemaParseSuccess()).isFalse();
    }

    @Test
    @DisplayName("위치와 현재·야간·거리 의도를 병원 검색 조건으로 전달한다")
    void consult_searchIntent_passesAllowedConditions() {
        AiAnalysisResult result = result(List.of("BLOOD_TEST"));
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        BigDecimal latitude = new BigDecimal("37.5665");
        BigDecimal longitude = new BigDecimal("126.9780");
        given(hospitalService.hospitalSearch(
                1L, null, null, latitude, longitude, null,
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
                1L, null, null, latitude, longitude, null,
                List.of("BLOOD_TEST"), List.of("DOG"),
                null, null, true, null, false, true, 1, 20, "distance"
        );
    }

    @Test
    @DisplayName("조류 축종은 일반 진료역량과 분리해 병원 검색 조건으로 전달한다")
    void consult_birdSpecies_passesSpeciesSeparatelyFromCapabilities() {
        AiAnalysisResult result = result(List.of("BLOOD_TEST"));
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        given(hospitalService.hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of("BLOOD_TEST"), List.of("BIRD"),
                null, null, null, null, false, false, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(), 1, 20, 0, 0));

        service.consult(
                1L,
                request("앵무새를 진료할 수 있는 병원을 찾아줘", PetSpecies.BIRD, "서울")
        );

        verify(hospitalService).hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of("BLOOD_TEST"), List.of("BIRD"),
                null, null, null, null, false, false, 1, 20, "name"
        );
    }

    @Test
    @DisplayName("Tool Calling Gateway가 선택한 조건으로 병원을 검색하고 서버 안내를 반환한다")
    void consult_toolCallingGateway_executesModelSelectedSearch() {
        AiAnalysisResult result = result(List.of("XRAY"));
        AtomicReference<String> toolOutput = new AtomicReference<>();
        doAnswer(invocation -> {
            AiToolExecutor executor = invocation.getArgument(1);
            toolOutput.set(executor.searchNearbyVets(new AiHospitalSearchToolCall(
                    result,
                    null,
                    true,
                    true,
                    HospitalSearchSort.DISTANCE
            )));
            return new AiGatewayConsultationResult(
                    result,
                    false,
                    true,
                    true,
                    List.of(new AiHospitalRecommendationResult(
                            10L,
                            5,
                            List.of(
                                    new AiRecommendationEvidenceResult(
                                            AiRecommendationEvidenceType.CAPABILITY,
                                            "XRAY"
                                    ),
                                    new AiRecommendationEvidenceResult(
                                            AiRecommendationEvidenceType.OPEN_NOW,
                                            "true"
                                    )
                            )
                    ))
            );
        }).when(aiGateway).consult(any(), any());
        BigDecimal latitude = new BigDecimal("37.5665");
        BigDecimal longitude = new BigDecimal("126.9780");
        given(hospitalService.hospitalSearch(
                1L, null, null, latitude, longitude, null,
                List.of("XRAY"), List.of("DOG"), null, null,
                true, null, false, true, 1, 20, "distance"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));
        given(hospitalService.getCapabilitiesByHospitalIds(List.of(10L)))
                .willReturn(Map.of(
                        10L,
                        List.of(CapabilityValue.DOG, CapabilityValue.XRAY)
                ));
        given(reviewQueryService.getEvidenceByHospitalIds(List.of(10L)))
                .willReturn(Map.of(10L, new HospitalReviewEvidence(
                        10L,
                        new BigDecimal("4.5"),
                        10L,
                        8L,
                        1L,
                        1L,
                        List.of(new ReviewExcerpt(
                                11L,
                                new BigDecimal("5.0"),
                                ReviewRatingBand.POSITIVE,
                                "친절하고 설명이 자세해요",
                                LocalDateTime.of(2026, 8, 12, 10, 0)
                        ))
                )));

        AiConsultationResponse response = service.consult(
                1L,
                new AiConsultationRequest(
                        "검사를 받을 수 있는 가까운 야간 병원을 알려줘",
                        PetSpecies.DOG,
                        null,
                        latitude,
                        longitude
                )
        );

        assertThat(response.hospitals()).containsExactly(hospital());
        assertThat(response.recommendations()).singleElement().satisfies(recommendation -> {
            assertThat(recommendation.hospital()).isEqualTo(hospital());
            assertThat(recommendation.recommendationScore()).isEqualTo(5);
            assertThat(recommendation.recommendationReason())
                    .isEqualTo("필요한 진료 역량을 보유하고 있습니다. 현재 진료 중입니다.");
            assertThat(recommendation.evidence())
                    .extracting(AiRecommendationEvidenceResult::type)
                    .containsExactly(
                            AiRecommendationEvidenceType.CAPABILITY,
                            AiRecommendationEvidenceType.OPEN_NOW
                    );
        });
        assertThat(toolOutput.get()).contains(
                "\"supportedSpecies\":[\"DOG\"]",
                "\"capabilities\":[\"XRAY\"]",
                "\"averageRating\":4.5",
                "\"positiveReviewCount\":8",
                "친절하고 설명이 자세해요"
        );
        assertThat(response.message()).isEqualTo("조건에 맞는 동물병원 1곳을 추천합니다.");
        verify(hospitalService).hospitalSearch(
                1L, null, null, latitude, longitude, null,
                List.of("XRAY"), List.of("DOG"), null, null,
                true, null, false, true, 1, 20, "distance"
        );
    }

    @Test
    void 실제_후보와_일치하지_않는_추천_근거는_거부한다() {
        AiHospitalCandidateEvidence candidate = new AiHospitalCandidateEvidence(
                hospital(),
                List.of(CapabilityValue.DOG),
                List.of(CapabilityValue.XRAY),
                HospitalReviewEvidence.empty(10L)
        );
        AiHospitalRecommendationResult recommendation =
                new AiHospitalRecommendationResult(
                        10L,
                        5,
                        List.of(new AiRecommendationEvidenceResult(
                                AiRecommendationEvidenceType.CAPABILITY,
                                "MRI"
                        ))
                );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "toRecommendationResponses",
                List.of(recommendation),
                List.of(candidate)
        )).isInstanceOfSatisfying(AiGatewayException.class, exception ->
                assertThat(exception.getFailureReason())
                        .isEqualTo(AiGatewayFailureReason.INVALID_RESPONSE));
    }

    @Test
    void 검색_후보가_있는데_추천_개수가_부족하면_거부한다() {
        List<AiHospitalCandidateEvidence> candidates = List.of(
                candidate(hospital(10L, "첫 병원", "1.0")),
                candidate(hospital(20L, "둘째 병원", "2.0"))
        );

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "toRecommendationResponses",
                List.of(recommendation(10L, 5)),
                candidates
        )).isInstanceOfSatisfying(AiGatewayException.class, exception ->
                assertThat(exception.getFailureReason())
                        .isEqualTo(AiGatewayFailureReason.INVALID_RESPONSE));
    }

    @Test
    void 추천_후검증_실패는_OpenAI_사용량과_Tool_성공을_보존한다() {
        AiAnalysisResult result = new AiAnalysisResult(
                List.of(),
                List.of("XRAY"),
                UrgencyLevel.MODERATE,
                List.of(),
                true,
                "gpt-4.1-mini",
                "doctorpet-ai-v8",
                180,
                50
        );
        doAnswer(invocation -> {
            AiToolExecutor executor = invocation.getArgument(1);
            executor.searchNearbyVets(new AiHospitalSearchToolCall(
                    result,
                    null,
                    null,
                    null,
                    HospitalSearchSort.NAME
            ));
            return new AiGatewayConsultationResult(
                    result,
                    false,
                    true,
                    true,
                    List.of()
            );
        }).when(aiGateway).consult(any(), any());
        given(hospitalService.hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of("XRAY"), List.of("DOG"), null, null,
                null, null, false, false, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L,
                request("검사 가능한 병원을 알려줘", PetSpecies.DOG, "서울")
        );

        ArgumentCaptor<AiConsultation> captor = ArgumentCaptor.forClass(AiConsultation.class);
        verify(repository).save(captor.capture());
        AiConsultation saved = captor.getValue();
        assertThat(response.fallback()).isTrue();
        assertThat(saved.getStatus()).isEqualTo(AiConsultationStatus.FAILED);
        assertThat(saved.getErrorType()).isEqualTo(AiGatewayFailureReason.INVALID_RESPONSE);
        assertThat(saved.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(saved.getPromptVersion()).isEqualTo("doctorpet-ai-v8");
        assertThat(saved.getPromptTokens()).isEqualTo(180);
        assertThat(saved.getCompletionTokens()).isEqualTo(50);
        assertThat(saved.getToolCallStatus()).isEqualTo(AiToolCallStatus.SUCCESS);
        assertThat(saved.isSchemaParseSuccess()).isFalse();
    }

    @Test
    void 검색_후보가_없으면_빈_추천을_허용한다() {
        List<AiHospitalRecommendationResponse> result = ReflectionTestUtils.invokeMethod(
                service,
                "toRecommendationResponses",
                List.of(),
                List.of()
        );

        assertThat(result).isEmpty();
    }

    @Test
    void 추천은_점수_내림차순이고_동점이면_거리순으로_정렬한다() {
        HospitalSearchResponse first = hospital(10L, "첫 병원", "2.0");
        HospitalSearchResponse second = hospital(20L, "둘째 병원", "1.0");
        HospitalSearchResponse third = hospital(30L, "셋째 병원", "3.0");
        List<AiHospitalCandidateEvidence> candidates = List.of(
                candidate(first), candidate(second), candidate(third));
        List<AiHospitalRecommendationResult> recommendations = List.of(
                recommendation(10L, 4),
                recommendation(30L, 5),
                recommendation(20L, 4)
        );

        List<AiHospitalRecommendationResponse> result = ReflectionTestUtils.invokeMethod(
                service,
                "toRecommendationResponses",
                recommendations,
                candidates
        );

        assertThat(result)
                .extracting(response -> response.hospital().hospitalId())
                .containsExactly(30L, 20L, 10L);
    }

    @Test
    @DisplayName("Tool 호출과 최종 응답의 진료역량 순서가 달라도 같은 조건으로 처리한다")
    void consult_toolCallingCapabilitiesInDifferentOrder_succeeds() {
        AiAnalysisResult toolAnalysis = result(List.of("XRAY", "ULTRASOUND"));
        AiAnalysisResult finalAnalysis = result(List.of("ULTRASOUND", "XRAY"));
        doAnswer(invocation -> {
            AiToolExecutor executor = invocation.getArgument(1);
            executor.searchNearbyVets(new AiHospitalSearchToolCall(
                    toolAnalysis,
                    null,
                    null,
                    null,
                    HospitalSearchSort.NAME
            ));
            return new AiGatewayConsultationResult(
                    finalAnalysis,
                    false,
                    true,
                    true,
                    List.of(businessStatusRecommendation(10L, 5))
            );
        }).when(aiGateway).consult(any(), any());
        given(hospitalService.hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of("XRAY", "ULTRASOUND"), List.of("DOG"), null, null,
                null, null, false, false, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L,
                new AiConsultationRequest(
                        "검사 가능한 병원을 알려줘",
                        PetSpecies.DOG,
                        "서울",
                        null,
                        null
                )
        );

        assertThat(response.fallback()).isFalse();
        assertThat(response.hospitals()).containsExactly(hospital());
        assertThat(response.structured().requiredCapabilities())
                .containsExactly("ULTRASOUND", "XRAY");
    }

    @Test
    @DisplayName("Tool 호출 단계가 HIGH이면 최종 응답의 긴급도가 낮아져도 응급 안내를 유지한다")
    void consult_toolCallingHighThenModerate_keepsEmergencyGuidance() {
        AiAnalysisResult toolAnalysis = new AiAnalysisResult(
                List.of(), List.of("XRAY"), UrgencyLevel.HIGH, List.of(), true,
                "gpt-4.1-mini", "doctorpet-ai-v4", 50, 10
        );
        AiAnalysisResult finalAnalysis = new AiAnalysisResult(
                List.of(), List.of("XRAY"), UrgencyLevel.MODERATE, List.of(), true,
                "gpt-4.1-mini", "doctorpet-ai-v4", 100, 20
        );
        doAnswer(invocation -> {
            AiToolExecutor executor = invocation.getArgument(1);
            executor.searchNearbyVets(new AiHospitalSearchToolCall(
                    toolAnalysis,
                    true,
                    null,
                    true,
                    HospitalSearchSort.NAME
            ));
            return new AiGatewayConsultationResult(
                    finalAnalysis,
                    false,
                    true,
                    true,
                    List.of(businessStatusRecommendation(10L, 5))
            );
        }).when(aiGateway).consult(any(), any());
        given(hospitalService.hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of("XRAY"), List.of("DOG"), null, null,
                null, true, false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L,
                request("상태가 갑자기 나빠졌어요", PetSpecies.DOG, "서울")
        );

        assertThat(response.structured().urgencyLevel()).isEqualTo(UrgencyLevel.HIGH);
        assertThat(response.message()).contains("응급 가능성");
        assertThat(response.locationRecommended()).isTrue();
        assertThat(response.hospitals()).containsExactly(hospital());
        verify(hospitalService).hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of("XRAY"), List.of("DOG"), null, null,
                null, true, false, true, 1, 20, "name"
        );
    }

    @Test
    @DisplayName("Tool Calling Gateway가 Tool 없이 HIGH를 반환하면 서버가 응급 검색을 강제한다")
    void consult_toolCallingHighWithoutTool_forcesEmergencySearch() {
        AiAnalysisResult high = new AiAnalysisResult(
                List.of(), List.of(), UrgencyLevel.HIGH, List.of(), true,
                "gpt-4.1-mini", "doctorpet-ai-v4", 100, 20
        );
        doAnswer(invocation -> new AiGatewayConsultationResult(
                high,
                false,
                true,
                false
        )).when(aiGateway).consult(any(), any());
        given(hospitalService.hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null,
                null, true, false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L,
                request("상태가 갑자기 나빠졌어요", PetSpecies.DOG, "서울")
        );

        assertThat(response.message()).contains("응급 상황");
        assertThat(response.hospitals()).containsExactly(hospital());
        verify(hospitalService).hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null,
                null, true, false, true, 1, 20, "name"
        );
    }

    @Test
    @DisplayName("모델의 일반 위치 필요 신호를 응급 위치 권장과 구분해 반환한다")
    void consult_toolCallingLocationRequired_returnsRequiredSignal() {
        AiAnalysisResult result = result(List.of());
        doAnswer(invocation -> new AiGatewayConsultationResult(
                result,
                true,
                true,
                false
        )).when(aiGateway).consult(any(), any());

        AiConsultationResponse response = service.consult(
                1L,
                request("가장 가까운 병원을 찾아줘", PetSpecies.DOG, null)
        );

        assertThat(response.locationRequired()).isTrue();
        assertThat(response.locationRecommended()).isFalse();
        assertThat(response.message()).contains("위치");
        verify(hospitalService, never()).hospitalSearch(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(Boolean.class), any(Boolean.class),
                any(Integer.class), any(Integer.class), any()
        );
    }

    @Test
    @DisplayName("새벽인데 지금 진료 가능한 요청은 야간 조건 없이 현재 영업만 적용한다")
    void consult_currentAvailabilityAtDawn_doesNotForceNightCare() {
        AiAnalysisResult result = result(List.of());
        given(aiGateway.analyze(any(AiAnalysisRequest.class))).willReturn(result);
        given(hospitalService.hospitalSearch(
                null, null, "서울", null, null, null,
                List.of(), List.of("CAT"), null, null, null, null,
                false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(), 1, 20, 0, 0));

        service.consult(null, request("새벽인데 지금 진료 가능한 병원", PetSpecies.CAT, "서울"));

        verify(hospitalService).hospitalSearch(
                null, null, "서울", null, null, null,
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
                1L, null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "name"
        )).willReturn(HospitalSearchPageResponse.of(List.of(hospital()), 1, 20, 1, 1));

        AiConsultationResponse response = service.consult(
                1L, request("상태가 갑자기 나빠졌어요", PetSpecies.DOG, "서울"));

        assertThat(response.message()).contains("입력한 지역");
        assertThat(response.locationRecommended()).isTrue();
        verify(hospitalService).hospitalSearch(
                1L, null, "서울", null, null, null,
                List.of(), List.of("DOG"), null, null, null, true,
                false, true, 1, 20, "name"
        );
    }

    @Test
    @DisplayName("응급 키워드는 AI를 호출하지 않고 응급 병원을 검색한다")
    void consult_emergencyKeyword_bypassesGateway() {
        given(emergencyKeywordDetector.isEmergency("강아지가 호흡곤란을 보여요")).willReturn(true);
        given(hospitalService.hospitalSearch(
                1L, null, "서울", null, null, null,
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
                1L, null, null, latitude, longitude, null,
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
                1L, null, null, latitude, longitude, null,
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
                1L, null, null, latitude, longitude, null,
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
                1L, null, "서울", null, null, null,
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
                List.of("ONSET_TIME"),
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
                true,
                false
        );
    }

    private HospitalSearchResponse hospital(Long hospitalId, String name, String distanceKm) {
        return new HospitalSearchResponse(
                hospitalId,
                name,
                "서울특별시 중구",
                new BigDecimal(distanceKm),
                BusinessStatus.OPEN,
                PartnershipStatus.PARTNER,
                true,
                null,
                true,
                false
        );
    }

    private AiHospitalCandidateEvidence candidate(HospitalSearchResponse hospital) {
        return new AiHospitalCandidateEvidence(
                hospital,
                List.of(CapabilityValue.DOG),
                List.of(CapabilityValue.XRAY),
                HospitalReviewEvidence.empty(hospital.hospitalId())
        );
    }

    private AiHospitalRecommendationResult recommendation(Long hospitalId, int score) {
        return new AiHospitalRecommendationResult(
                hospitalId,
                score,
                List.of(new AiRecommendationEvidenceResult(
                        AiRecommendationEvidenceType.CAPABILITY,
                        "XRAY"
                ))
        );
    }

    private AiHospitalRecommendationResult businessStatusRecommendation(
            Long hospitalId,
            int score
    ) {
        return new AiHospitalRecommendationResult(
                hospitalId,
                score,
                List.of(new AiRecommendationEvidenceResult(
                        AiRecommendationEvidenceType.BUSINESS_STATUS,
                        "OPEN"
                ))
        );
    }
}
