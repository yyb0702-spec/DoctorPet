package com.doctorpet.domain.ai.service;

import com.doctorpet.domain.ai.dto.request.AiConsultationRequest;
import com.doctorpet.domain.ai.dto.response.AiConsultationResponse;
import com.doctorpet.domain.ai.entity.AiConsultation;
import com.doctorpet.domain.ai.model.AiHospitalSearchIntent;
import com.doctorpet.domain.ai.model.AiStructuredResult;
import com.doctorpet.domain.ai.repository.AiConsultationRepository;
import com.doctorpet.domain.ai.support.AiHospitalSearchIntentExtractor;
import com.doctorpet.domain.ai.support.EmergencyKeywordDetector;
import com.doctorpet.domain.ai.support.SymptomTextMasker;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.global.gateway.ai.AiGateway;
import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** AI 외부 호출은 트랜잭션 밖에서 수행하고 결과 로그만 별도 저장한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConsultationService {

    private static final int HOSPITAL_SEARCH_PAGE = 1;
    private static final int HOSPITAL_SEARCH_SIZE = 20;
    private static final String NAME_SORT = "name";
    private static final String DISTANCE_SORT = "distance";
    private static final String DISCLAIMER =
            "AI 분석은 참고용이며 진단이나 처방을 대신하지 않습니다. 정확한 판단은 동물병원에서 받아 주세요.";
    private static final String SUCCESS_MESSAGE = "증상을 바탕으로 필요한 진료역량을 정리했습니다.";
    private static final String EMERGENCY_DISTANCE_MESSAGE =
            "응급 상황일 수 있습니다. 현재 진료 가능한 동물병원을 가까운 순으로 안내해 드립니다. 지체하지 말고 방문해 주세요.";
    private static final String EMERGENCY_REGION_MESSAGE =
            "응급 상황일 수 있습니다. 입력한 지역에서 현재 진료 가능한 동물병원을 안내해 드립니다. 지체하지 말고 방문해 주세요.";
    private static final String EMERGENCY_LOCATION_RECOMMENDED_MESSAGE =
            "응급 상황일 수 있습니다. 가까운 응급 동물병원을 안내하려면 현재 위치를 제공하거나 지역을 입력해 주세요.";
    private static final String EMERGENCY_SEARCH_FAILED_MESSAGE =
            "응급 상황일 수 있지만 현재 병원 정보를 불러오지 못했습니다. 병원 검색에서 가까운 동물병원을 직접 확인하거나 즉시 연락해 주세요.";
    private static final String LOCATION_REQUIRED_MESSAGE =
            "가까운 병원을 찾으려면 위치를 제공하거나 지역을 입력해 주세요.";
    private static final String FALLBACK_MESSAGE =
            "현재 AI 상담을 이용하기 어렵습니다. 병원 검색에서 필요한 조건을 직접 선택해 주세요.";
    private static final Set<CapabilityValue> SEARCH_CAPABILITIES = EnumSet.complementOf(
            EnumSet.of(CapabilityValue.DOG, CapabilityValue.CAT));

    private final AiGateway aiGateway;
    private final AiConsultationRepository aiConsultationRepository;
    private final SymptomTextMasker symptomTextMasker;
    private final HospitalService hospitalService;
    private final AiHospitalSearchIntentExtractor searchIntentExtractor;
    private final EmergencyKeywordDetector emergencyKeywordDetector;

    public AiConsultationResponse consult(Long memberId, AiConsultationRequest request) {
        String maskedSymptomText = symptomTextMasker.mask(request.symptomText());
        long startedAt = System.nanoTime();

        if (emergencyKeywordDetector.isEmergency(request.symptomText())) {
            return consultEmergency(memberId, request, maskedSymptomText, startedAt);
        }

        try {
            AiAnalysisResult result = aiGateway.analyze(
                    new AiAnalysisRequest(request.symptomText(), request.species()));
            validateRequiredCapabilities(result.requiredCapabilities());
            AiHospitalSearchIntent searchIntent = searchIntentExtractor.extract(request.symptomText());
            boolean emergency = result.urgencyLevel() == UrgencyLevel.HIGH;
            if (emergency) {
                searchIntent = searchIntent.withEmergencyVisit();
            }
            if (!emergency && requiresLocation(request, searchIntent)) {
                return locationRequired(memberId, maskedSymptomText, result, startedAt);
            }
            if (emergency && requiresEmergencyLocation(request)) {
                return emergencyWithoutLocation(
                        memberId,
                        maskedSymptomText,
                        result,
                        startedAt
                );
            }
            List<HospitalSearchResponse> hospitals;
            try {
                hospitals = searchHospitals(request, result, searchIntent, emergency);
            } catch (RuntimeException exception) {
                log.error("병원 검색 Tool 호출에 실패했습니다.", exception);
                return toolFallback(
                        memberId,
                        maskedSymptomText,
                        result,
                        startedAt,
                        emergency && !hasLocation(request)
                );
            }
            int latencyMs = elapsedMillis(startedAt);
            aiConsultationRepository.save(AiConsultation.success(
                    memberId, maskedSymptomText, result, latencyMs));
            return new AiConsultationResponse(
                    AiStructuredResult.from(result),
                    hospitals,
                    DISCLAIMER,
                    emergency ? emergencyMessage(request) : SUCCESS_MESSAGE,
                    false,
                    emergency && !hasLocation(request)
            );
        } catch (AiGatewayException exception) {
            return fallback(memberId, maskedSymptomText, exception.getFailureReason(), startedAt);
        }
    }

    private List<HospitalSearchResponse> searchHospitals(
            AiConsultationRequest request,
            AiAnalysisResult result,
            AiHospitalSearchIntent intent,
            boolean emergency
    ) {
        boolean distanceSort = hasLocation(request)
                && (emergency || intent.distance());
        return hospitalService.hospitalSearch(
                null,
                request.region(),
                request.latitude(),
                request.longitude(),
                null,
                result.requiredCapabilities(),
                List.of(request.species().name()),
                null,
                null,
                intent.nightCare() ? true : null,
                emergency ? true : null,
                false,
                intent.openNow(),
                HOSPITAL_SEARCH_PAGE,
                HOSPITAL_SEARCH_SIZE,
                distanceSort ? DISTANCE_SORT : NAME_SORT
        ).content();
    }

    private AiConsultationResponse consultEmergency(
            Long memberId,
            AiConsultationRequest request,
            String maskedSymptomText,
            long startedAt
    ) {
        AiAnalysisResult result = emergencyResult();
        AiHospitalSearchIntent intent = searchIntentExtractor.extract(request.symptomText())
                .withEmergencyVisit();
        if (requiresEmergencyLocation(request)) {
            return emergencyWithoutLocation(
                    memberId,
                    maskedSymptomText,
                    result,
                    startedAt
            );
        }
        List<HospitalSearchResponse> hospitals;
        try {
            hospitals = searchHospitals(request, result, intent, true);
        } catch (RuntimeException exception) {
            log.error("응급 병원 검색 Tool 호출에 실패했습니다.", exception);
            aiConsultationRepository.save(AiConsultation.toolFailed(
                    memberId, maskedSymptomText, result, elapsedMillis(startedAt)));
            return new AiConsultationResponse(
                    AiStructuredResult.from(result),
                    List.of(),
                    DISCLAIMER,
                    EMERGENCY_SEARCH_FAILED_MESSAGE,
                    true,
                    !hasLocation(request)
            );
        }
        aiConsultationRepository.save(AiConsultation.success(
                memberId, maskedSymptomText, result, elapsedMillis(startedAt)));
        return new AiConsultationResponse(
                AiStructuredResult.from(result),
                hospitals,
                DISCLAIMER,
                emergencyMessage(request),
                false,
                !hasLocation(request)
        );
    }

    private AiAnalysisResult emergencyResult() {
        return new AiAnalysisResult(
                List.of(),
                List.of(),
                UrgencyLevel.HIGH,
                List.of("증상이 시작된 시각과 변화를 병원에 알려 주세요."),
                true,
                null,
                null,
                null,
                null
        );
    }

    private boolean requiresLocation(
            AiConsultationRequest request,
            AiHospitalSearchIntent intent
    ) {
        return intent.distance()
                && request.latitude() == null
                && !StringUtils.hasText(request.region());
    }

    private boolean requiresEmergencyLocation(AiConsultationRequest request) {
        return !hasLocation(request) && !StringUtils.hasText(request.region());
    }

    private boolean hasLocation(AiConsultationRequest request) {
        return request.latitude() != null && request.longitude() != null;
    }

    private String emergencyMessage(AiConsultationRequest request) {
        if (hasLocation(request)) {
            return EMERGENCY_DISTANCE_MESSAGE;
        }
        return EMERGENCY_REGION_MESSAGE;
    }

    private AiConsultationResponse emergencyWithoutLocation(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            long startedAt
    ) {
        aiConsultationRepository.save(AiConsultation.successWithoutTool(
                memberId, maskedSymptomText, result, elapsedMillis(startedAt)));
        return new AiConsultationResponse(
                AiStructuredResult.from(result),
                List.of(),
                DISCLAIMER,
                EMERGENCY_LOCATION_RECOMMENDED_MESSAGE,
                false,
                true
        );
    }

    private AiConsultationResponse locationRequired(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            long startedAt
    ) {
        aiConsultationRepository.save(AiConsultation.successWithoutTool(
                memberId, maskedSymptomText, result, elapsedMillis(startedAt)));
        return new AiConsultationResponse(
                AiStructuredResult.from(result),
                List.of(),
                DISCLAIMER,
                LOCATION_REQUIRED_MESSAGE,
                false,
                false
        );
    }

    private void validateRequiredCapabilities(List<String> requiredCapabilities) {
        boolean valid = requiredCapabilities.stream().allMatch(this::isSearchCapability);
        if (!valid) {
            throw new AiGatewayException(
                    AiGatewayFailureReason.INVALID_RESPONSE,
                    "AI 응답에 허용되지 않은 진료역량이 포함되어 있습니다."
            );
        }
    }

    private boolean isSearchCapability(String value) {
        if (value == null) {
            return false;
        }
        try {
            CapabilityValue capability = CapabilityValue.valueOf(value);
            return SEARCH_CAPABILITIES.contains(capability);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private AiConsultationResponse fallback(
            Long memberId,
            String maskedSymptomText,
            AiGatewayFailureReason failureReason,
            long startedAt
    ) {
        aiConsultationRepository.save(AiConsultation.failed(
                memberId, maskedSymptomText, failureReason, elapsedMillis(startedAt)));
        return new AiConsultationResponse(
                null, List.of(), DISCLAIMER, FALLBACK_MESSAGE, true, false);
    }

    private AiConsultationResponse toolFallback(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            long startedAt,
            boolean locationRecommended
    ) {
        aiConsultationRepository.save(AiConsultation.toolFailed(
                memberId,
                maskedSymptomText,
                result,
                elapsedMillis(startedAt)
        ));
        return new AiConsultationResponse(
                null,
                List.of(),
                DISCLAIMER,
                FALLBACK_MESSAGE,
                true,
                locationRecommended
        );
    }

    private int elapsedMillis(long startedAt) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return (int) Math.min(elapsed, Integer.MAX_VALUE);
    }
}
