package com.doctorpet.global.gateway.ai.fake;

import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeAiGatewayTest {

    private FakeAiGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new FakeAiGateway();
    }

    @Test
    @DisplayName("기본 결과는 구조화 출력 5필드를 반환하고 LLM 관측값은 비어 있다")
    void analyzeReturnsDefaultStructuredResult() {
        AiAnalysisResult result = gateway.analyze(
                new AiAnalysisRequest("구토를 반복해요", PetSpecies.DOG));

        assertEquals(UrgencyLevel.MODERATE, result.urgencyLevel());
        assertEquals(List.of("BLOOD_TEST"), result.requiredCapabilities());
        assertEquals(true, result.recommendVetVisit());
        assertNull(result.model());
        assertNull(result.promptVersion());
        assertNull(result.promptTokens());
        assertNull(result.completionTokens());
    }

    @Test
    @DisplayName("요청과 HIGH 결과를 주입해 상위 안전 분기 테스트에 사용할 수 있다")
    void resultAndRequestCanBeControlled() {
        AiAnalysisResult highResult = new AiAnalysisResult(
                List.of("EMERGENCY"),
                List.of("XRAY"),
                UrgencyLevel.HIGH,
                List.of("증상 시작 시각을 확인해 주세요."),
                true,
                null,
                null,
                null,
                null
        );
        gateway.stubResult(highResult);
        AiAnalysisRequest request = new AiAnalysisRequest("호흡이 불안정해요", PetSpecies.CAT);

        AiAnalysisResult result = gateway.analyze(request);

        assertEquals(highResult, result);
        assertEquals(request, gateway.lastRequest());
    }

    @Test
    @DisplayName("타임아웃 실패를 주입하면 공통 실패 사유를 유지해 던진다")
    void timeoutFailureCanBeInjected() {
        gateway.stubFailure(new AiGatewayException(
                AiGatewayFailureReason.TIMEOUT,
                "AI 응답 시간이 초과되었습니다."
        ));

        AiGatewayException exception = assertThrows(
                AiGatewayException.class,
                () -> gateway.analyze(new AiAnalysisRequest("기침을 해요", PetSpecies.DOG))
        );

        assertEquals(AiGatewayFailureReason.TIMEOUT, exception.getFailureReason());
    }

    @Test
    @DisplayName("구조화 결과의 목록은 방어적으로 복사된다")
    void structuredListsAreDefensivelyCopied() {
        List<String> capabilities = new ArrayList<>(List.of("XRAY"));
        AiAnalysisResult result = new AiAnalysisResult(
                List.of("ORTHOPEDIC"),
                capabilities,
                UrgencyLevel.LOW,
                List.of("보행 상태를 확인해 주세요."),
                true,
                null,
                null,
                null,
                null
        );

        capabilities.add("MRI");

        assertEquals(List.of("XRAY"), result.requiredCapabilities());
        assertThrows(UnsupportedOperationException.class,
                () -> result.requiredCapabilities().add("CT"));
    }
}
