package com.doctorpet.domain.ai.service;

import com.doctorpet.domain.ai.dto.request.AiConsultationRequest;
import com.doctorpet.domain.ai.dto.response.AiConsultationResponse;
import com.doctorpet.domain.ai.entity.AiConsultation;
import com.doctorpet.domain.ai.model.AiStructuredResult;
import com.doctorpet.domain.ai.repository.AiConsultationRepository;
import com.doctorpet.domain.ai.support.SymptomTextMasker;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchResponse;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.global.gateway.ai.AiGateway;
import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
    private static final String HOSPITAL_SEARCH_SORT = "name";
    private static final String DISCLAIMER =
            "AI 분석은 참고용이며 진단이나 처방을 대신하지 않습니다. 정확한 판단은 동물병원에서 받아 주세요.";
    private static final String SUCCESS_MESSAGE = "증상을 바탕으로 필요한 진료역량을 정리했습니다.";
    private static final String FALLBACK_MESSAGE =
            "현재 AI 상담을 이용하기 어렵습니다. 병원 검색에서 필요한 조건을 직접 선택해 주세요.";
    private static final Set<CapabilityValue> SEARCH_CAPABILITIES = EnumSet.complementOf(
            EnumSet.of(CapabilityValue.DOG, CapabilityValue.CAT));

    private final AiGateway aiGateway;
    private final AiConsultationRepository aiConsultationRepository;
    private final SymptomTextMasker symptomTextMasker;
    private final HospitalService hospitalService;

    public AiConsultationResponse consult(Long memberId, AiConsultationRequest request) {
        String maskedSymptomText = symptomTextMasker.mask(request.symptomText());
        long startedAt = System.nanoTime();

        try {
            AiAnalysisResult result = aiGateway.analyze(
                    new AiAnalysisRequest(request.symptomText(), request.species()));
            validateRequiredCapabilities(result.requiredCapabilities());
            List<HospitalSearchResponse> hospitals;
            try {
                hospitals = searchHospitals(request, result);
            } catch (RuntimeException exception) {
                log.error("병원 검색 Tool 호출에 실패했습니다.", exception);
                return toolFallback(memberId, maskedSymptomText, result, startedAt);
            }
            int latencyMs = elapsedMillis(startedAt);
            aiConsultationRepository.save(AiConsultation.success(
                    memberId, maskedSymptomText, result, latencyMs));
            return new AiConsultationResponse(
                    AiStructuredResult.from(result), hospitals, DISCLAIMER, SUCCESS_MESSAGE, false);
        } catch (AiGatewayException exception) {
            return fallback(memberId, maskedSymptomText, exception.getFailureReason(), startedAt);
        }
    }

    private List<HospitalSearchResponse> searchHospitals(
            AiConsultationRequest request,
            AiAnalysisResult result
    ) {
        return hospitalService.hospitalSearch(
                null,
                request.region(),
                null,
                null,
                null,
                result.requiredCapabilities(),
                List.of(request.species().name()),
                null,
                null,
                null,
                null,
                false,
                false,
                HOSPITAL_SEARCH_PAGE,
                HOSPITAL_SEARCH_SIZE,
                HOSPITAL_SEARCH_SORT
        ).content();
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
        return new AiConsultationResponse(null, List.of(), DISCLAIMER, FALLBACK_MESSAGE, true);
    }

    private AiConsultationResponse toolFallback(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            long startedAt
    ) {
        aiConsultationRepository.save(AiConsultation.toolFailed(
                memberId,
                maskedSymptomText,
                result,
                elapsedMillis(startedAt)
        ));
        return new AiConsultationResponse(null, List.of(), DISCLAIMER, FALLBACK_MESSAGE, true);
    }

    private int elapsedMillis(long startedAt) {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        return (int) Math.min(elapsed, Integer.MAX_VALUE);
    }
}
