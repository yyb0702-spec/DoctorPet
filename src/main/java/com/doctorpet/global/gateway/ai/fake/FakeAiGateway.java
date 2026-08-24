package com.doctorpet.global.gateway.ai.fake;

import com.doctorpet.global.gateway.ai.AiGateway;
import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 실제 LLM을 호출하지 않는 로컬·테스트용 AI 게이트웨이.
 *
 * <p>{@code ai.gateway=fake}로 명시한 경우에만 등록되며, 정상 결과와 실패를 주입해
 * 상위 상담 서비스의 안전 분기와 fallback을 검증할 수 있다.
 */
@Component
@ConditionalOnProperty(name = "ai.gateway", havingValue = "fake")
public class FakeAiGateway implements AiGateway {

    private static final AiAnalysisResult DEFAULT_RESULT = new AiAnalysisResult(
            List.of("GENERAL"),
            List.of("BLOOD_TEST"),
            UrgencyLevel.MODERATE,
            List.of("증상이 시작된 시점과 지속 시간을 확인해 주세요."),
            true,
            null,
            null,
            null,
            null
    );

    private volatile AiAnalysisResult result = DEFAULT_RESULT;
    private volatile AiGatewayException failure;
    private volatile AiAnalysisRequest lastRequest;

    @Override
    public AiAnalysisResult analyze(AiAnalysisRequest request) {
        lastRequest = request;
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    public void stubResult(AiAnalysisResult result) {
        this.result = result;
        this.failure = null;
    }

    public void stubFailure(AiGatewayException failure) {
        this.failure = failure;
    }

    public AiAnalysisRequest lastRequest() {
        return lastRequest;
    }

    public void reset() {
        result = DEFAULT_RESULT;
        failure = null;
        lastRequest = null;
    }
}
