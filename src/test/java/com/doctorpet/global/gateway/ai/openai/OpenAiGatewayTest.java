package com.doctorpet.global.gateway.ai.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiGatewayConsultationResult;
import com.doctorpet.global.gateway.ai.tool.AiHospitalSearchToolCall;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class OpenAiGatewayTest {

    private MockRestServiceServer server;
    private OpenAiGateway gateway;

    @BeforeEach
    void setUp() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setBaseUrl("https://api.openai.test/v1");
        properties.setApiKey("test-key");
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey());
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new OpenAiGateway(properties, new ObjectMapper(), builder.build());
    }

    @Test
    @DisplayName("모델의 병원 검색 Tool Call을 실행하고 결과를 돌려준 뒤 최종 구조화 응답을 반환한다")
    void consult_toolCall_executesToolAndReturnsFinalResponse() {
        server.expect(once(), requestTo("https://api.openai.test/v1/responses"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.tools[0].name").value("searchNearbyVets"))
                .andExpect(jsonPath("$.parallel_tool_calls").value(false))
                .andRespond(withSuccess(firstToolCallResponse(), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.openai.test/v1/responses"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.input[1].type").value("function_call"))
                .andExpect(jsonPath("$.input[2].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[2].call_id").value("call_1"))
                .andRespond(withSuccess(finalResponse(false), MediaType.APPLICATION_JSON));
        AtomicReference<AiHospitalSearchToolCall> captured = new AtomicReference<>();

        AiGatewayConsultationResult result = gateway.consult(
                new AiAnalysisRequest("강아지가 다리를 절어요", PetSpecies.DOG),
                call -> {
                    captured.set(call);
                    return "{\"hospitals\":[{\"hospitalId\":10,\"name\":\"서울동물병원\"}]}";
                }
        );

        server.verify();
        assertThat(result.toolCallingHandled()).isTrue();
        assertThat(result.toolCalled()).isTrue();
        assertThat(result.analysis().requiredCapabilities()).containsExactly("XRAY");
        assertThat(result.analysis().promptTokens()).isEqualTo(180);
        assertThat(result.analysis().completionTokens()).isEqualTo(50);
        assertThat(captured.get().openNow()).isTrue();
        assertThat(captured.get().analysis().requiredCapabilities()).containsExactly("XRAY");
    }

    @Test
    @DisplayName("위치가 필요한 경우 Tool을 실행하지 않고 구조화 위치 신호를 반환한다")
    void consult_locationRequired_doesNotExecuteTool() {
        server.expect(once(), requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess(finalResponse(true), MediaType.APPLICATION_JSON));

        AiGatewayConsultationResult result = gateway.consult(
                new AiAnalysisRequest("가장 가까운 병원을 찾아줘", PetSpecies.DOG),
                call -> {
                    throw new AssertionError("Tool이 호출되면 안 됩니다.");
                }
        );

        server.verify();
        assertThat(result.toolCalled()).isFalse();
        assertThat(result.locationRequired()).isTrue();
    }

    @Test
    @DisplayName("최종 구조화 응답의 필수 필드가 누락되면 INVALID_RESPONSE로 변환한다")
    void consult_missingFinalRequiredField_throwsInvalidResponse() {
        server.expect(once(), requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess(finalResponseWithoutRequiredCapabilities(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.consult(
                new AiAnalysisRequest("강아지가 기침해요", PetSpecies.DOG),
                call -> "{\"hospitals\":[]}"))
                .isInstanceOfSatisfying(AiGatewayException.class, exception ->
                        assertThat(exception.getFailureReason())
                                .isEqualTo(AiGatewayFailureReason.INVALID_RESPONSE));
        server.verify();
    }

    @Test
    @DisplayName("Tool 인자의 필수 필드가 누락되면 실행하지 않고 INVALID_RESPONSE로 변환한다")
    void consult_missingToolRequiredField_throwsInvalidResponseBeforeExecution() {
        server.expect(once(), requestTo("https://api.openai.test/v1/responses"))
                .andRespond(withSuccess(toolCallResponseWithoutRequiredCapabilities(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.consult(
                new AiAnalysisRequest("강아지가 다리를 절어요", PetSpecies.DOG),
                call -> {
                    throw new AssertionError("잘못된 Tool 인자로 검색을 실행하면 안 됩니다.");
                }))
                .isInstanceOfSatisfying(AiGatewayException.class, exception ->
                        assertThat(exception.getFailureReason())
                                .isEqualTo(AiGatewayFailureReason.INVALID_RESPONSE));
        server.verify();
    }

    private String firstToolCallResponse() {
        String arguments = """
                {"possibleFocusAreas":["MUSCULOSKELETAL"],"requiredCapabilities":["XRAY"],"urgencyLevel":"MODERATE","preVisitCheckpoints":["증상 시작 시점"],"recommendVetVisit":true,"emergency":null,"nightCare":null,"openNow":true,"sort":"NAME"}
                """.trim().replace("\"", "\\\"");
        return """
                {
                  "id":"resp_1",
                  "status":"completed",
                  "output":[{
                    "type":"function_call",
                    "call_id":"call_1",
                    "name":"searchNearbyVets",
                    "arguments":"%s"
                  }],
                  "usage":{"input_tokens":100,"output_tokens":20}
                }
                """.formatted(arguments);
    }

    private String finalResponse(boolean locationRequired) {
        String output = """
                {"possibleFocusAreas":["MUSCULOSKELETAL"],"requiredCapabilities":["XRAY"],"urgencyLevel":"MODERATE","preVisitCheckpoints":["증상 시작 시점"],"recommendVetVisit":true,"message":"조건에 맞는 병원을 확인했습니다.","locationRequired":%s}
                """.formatted(locationRequired).trim().replace("\"", "\\\"");
        return """
                {
                  "id":"resp_2",
                  "status":"completed",
                  "output":[{"type":"message","content":[{"type":"output_text","text":"%s"}]}],
                  "usage":{"input_tokens":80,"output_tokens":30}
                }
                """.formatted(output);
    }

    private String finalResponseWithoutRequiredCapabilities() {
        String output = """
                {"possibleFocusAreas":["RESPIRATORY"],"urgencyLevel":"MODERATE","preVisitCheckpoints":["기침 횟수"],"recommendVetVisit":true,"message":"진료를 권장합니다.","locationRequired":false}
                """.trim().replace("\"", "\\\"");
        return """
                {
                  "id":"resp_invalid_final",
                  "status":"completed",
                  "output":[{"type":"message","content":[{"type":"output_text","text":"%s"}]}],
                  "usage":{"input_tokens":40,"output_tokens":20}
                }
                """.formatted(output);
    }

    private String toolCallResponseWithoutRequiredCapabilities() {
        String arguments = """
                {"possibleFocusAreas":["MUSCULOSKELETAL"],"urgencyLevel":"MODERATE","preVisitCheckpoints":["증상 시작 시점"],"recommendVetVisit":true,"emergency":null,"nightCare":null,"openNow":true,"sort":"NAME"}
                """.trim().replace("\"", "\\\"");
        return """
                {
                  "id":"resp_invalid_tool",
                  "status":"completed",
                  "output":[{
                    "type":"function_call",
                    "call_id":"call_invalid",
                    "name":"searchNearbyVets",
                    "arguments":"%s"
                  }],
                  "usage":{"input_tokens":50,"output_tokens":10}
                }
                """.formatted(arguments);
    }
}
