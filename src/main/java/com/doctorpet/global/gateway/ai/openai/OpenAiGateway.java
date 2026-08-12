package com.doctorpet.global.gateway.ai.openai;

import com.doctorpet.domain.hospital.entity.CapabilityType;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.global.gateway.ai.AiGateway;
import com.doctorpet.global.gateway.ai.AiGatewayException;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisRequest;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.AiFocusArea;
import com.doctorpet.global.gateway.ai.dto.AiGatewayConsultationResult;
import com.doctorpet.global.gateway.ai.dto.AiPreVisitCheckpoint;
import com.doctorpet.global.gateway.ai.dto.AiRecommendationEvidenceType;
import com.doctorpet.global.gateway.ai.tool.AiHospitalSearchToolCall;
import com.doctorpet.global.gateway.ai.tool.AiToolExecutor;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI Responses API의 제한적 Tool Calling 구현.
 *
 * <p>상담 한 건은 최대 두 번의 OpenAI 호출로 처리한다. 1차 호출에서 모델이 병원 검색 필요 여부를 결정하고,
 * {@code searchNearbyVets}를 호출하면 서버가 전달받은 {@link AiToolExecutor}로 실제 병원을 검색한다. 이후
 * 1차 function call과 서버 검색 결과를 2차 호출에 함께 전달해 최종 구조화 응답을 받는다. Tool은 상담당
 * 최대 한 번만 허용하며, 공급자 응답 저장 상태에 의존하지 않도록 모든 요청에 {@code store=false}를 사용한다.
 */
@Component
@EnableConfigurationProperties(OpenAiProperties.class)
@ConditionalOnProperty(name = "ai.gateway", havingValue = "openai")
public class OpenAiGateway implements AiGateway {

    static final String SEARCH_TOOL_NAME = "searchNearbyVets";
    private static final String RESPONSES_PATH = "/responses";
    private static final String PROMPT_PATH = "prompts/ai-consultation-v8.txt";

    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String instructions;
    private final OpenAiCircuitBreaker circuitBreaker;

    @Autowired
    public OpenAiGateway(OpenAiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, buildRestClient(properties));
    }

    OpenAiGateway(OpenAiProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.instructions = loadInstructions();
        this.circuitBreaker = new OpenAiCircuitBreaker(
                properties.getCircuitBreakerFailureThreshold(),
                properties.getCircuitBreakerOpenMs()
        );
    }

    @Override
    public AiAnalysisResult analyze(AiAnalysisRequest request) {
        return consult(request, call -> "{\"hospitals\":[]}").analysis();
    }

    @Override
    public AiGatewayConsultationResult consult(
            AiAnalysisRequest request,
            AiToolExecutor toolExecutor
    ) {
        // 이미 회로가 열려 있으면 외부 HTTP 요청을 보내지 않는다. 차단 자체는 새 실패로 다시 집계하지 않도록
        // try 밖에서 검사한다.
        boolean halfOpenProbe = circuitBreaker.beforeCall();
        try {
            AiGatewayConsultationResult result = doConsult(request, toolExecutor);
            // HALF_OPEN 상태에서는 시험 요청 소유자의 성공만 회로를 닫을 수 있다.
            circuitBreaker.onSuccess(halfOpenProbe);
            return result;
        } catch (AiGatewayException exception) {
            // 공급자 장애만 회로 실패로 집계한다. 잘못된 구조화 응답은 요청/모델 출력 문제일 수 있으므로
            // Circuit Breaker를 열지 않는다.
            if (exception.getFailureReason() == AiGatewayFailureReason.TIMEOUT
                    || exception.getFailureReason() == AiGatewayFailureReason.TEMPORARY_UNAVAILABLE) {
                circuitBreaker.onFailure(halfOpenProbe);
            } else {
                circuitBreaker.onIgnoredFailure(halfOpenProbe);
            }
            throw exception;
        } catch (RuntimeException exception) {
            // Tool 실행 등 추적 대상이 아닌 런타임 예외도 HALF_OPEN 시험 상태는 반드시 해제해야 한다.
            circuitBreaker.onIgnoredFailure(halfOpenProbe);
            throw exception;
        }
    }

    private AiGatewayConsultationResult doConsult(
            AiAnalysisRequest request,
            AiToolExecutor toolExecutor
    ) {
        // 1차 호출: 증상·축종과 Tool 스키마를 전달해 모델이 검색 필요 여부를 결정하게 한다.
        JsonNode first = invoke(initialRequest(request));
        List<JsonNode> toolCalls = functionCalls(first);
        if (toolCalls.size() > 1) {
            throw invalidResponse("허용된 Tool 호출 횟수를 초과했습니다.");
        }

        int promptTokens = usage(first, "input_tokens");
        int completionTokens = usage(first, "output_tokens");
        if (toolCalls.isEmpty()) {
            // 위치가 부족하거나 병원 검색이 불필요하면 모델이 Tool 없이 최종 구조화 응답을 바로 반환한다.
            OpenAiFinalOutput output = parseFinalOutput(first);
            return result(output, promptTokens, completionTokens, false);
        }

        // Tool은 한 번만 허용하므로 아래에서는 유일한 function_call만 처리한다.
        JsonNode toolCallNode = toolCalls.get(0);
        if (!SEARCH_TOOL_NAME.equals(toolCallNode.path("name").asText())) {
            throw invalidResponse("허용되지 않은 Tool 호출입니다.");
        }
        // Responses API의 arguments는 JSON 객체가 아니라 JSON 문자열이므로 OpenAI 전용 DTO로 한 번 파싱한다.
        OpenAiToolArguments arguments = parseToolArguments(toolCallNode.path("arguments").asText());
        // Service가 넘긴 람다가 실제로 실행되는 지점이다. 반환 JSON은 아래 2차 OpenAI 호출의 Tool 결과가 된다.
        String toolOutput = toolExecutor.searchNearbyVets(
                toToolCall(arguments, promptTokens, completionTokens));

        // 2차 호출: 최초 사용자 입력 + 모델의 function_call + 서버의 function_call_output을 모두 재전송한다.
        JsonNode second = invoke(followUpRequest(
                request,
                first,
                toolCallNode.path("call_id").asText(),
                toolOutput
        ));
        promptTokens += usage(second, "input_tokens");
        completionTokens += usage(second, "output_tokens");
        // 연쇄 검색과 반복 호출을 막기 위해 2차 응답에서는 추가 Tool Call을 허용하지 않는다.
        if (!functionCalls(second).isEmpty()) {
            throw invalidResponse("병원 검색 Tool은 한 번만 호출할 수 있습니다.");
        }
        return result(parseFinalOutput(second), promptTokens, completionTokens, true);
    }

    private AiGatewayConsultationResult result(
            OpenAiFinalOutput output,
            int promptTokens,
            int completionTokens,
            boolean toolCalled
    ) {
        // OpenAI JSON에 있는 분석 5필드와 응답 usage에서 읽은 운영 정보를 제공자 중립 결과로 합친다.
        AiAnalysisResult analysis;
        try {
            analysis = output.analysis().toResult(
                    properties.getModel(),
                    properties.getPromptVersion(),
                    promptTokens,
                    completionTokens
            );
            return new AiGatewayConsultationResult(
                    analysis,
                    output.locationRequired().booleanValue(),
                    true, // 이 구현체는 Tool Calling 전체 흐름을 처리했음
                    toolCalled,
                    output.recommendations()
            );
        } catch (NullPointerException exception) {
            // JSON 문법이 유효해도 필수 필드가 누락되면 DTO 생성 단계에서 실패할 수 있다.
            // 외부 응답 계약 위반으로 분류해 상위 서비스의 fallback 경로로 전달한다.
            throw new AiGatewayException(
                    AiGatewayFailureReason.INVALID_RESPONSE,
                    "OpenAI 최종 구조화 응답의 필수 필드가 누락되었습니다.",
                    exception
            );
        }
    }

    private JsonNode invoke(Map<String, Object> body) {
        try {
            String responseBody = restClient.post()
                    .uri(RESPONSES_PATH)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (responseBody == null) {
                throw invalidResponse("OpenAI 응답 본문이 없습니다.");
            }
            JsonNode response = objectMapper.readTree(responseBody);
            if (!"completed".equals(response.path("status").asText())) {
                throw invalidResponse("OpenAI 응답이 완료 상태가 아닙니다.");
            }
            return response;
        } catch (AiGatewayException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new AiGatewayException(
                    AiGatewayFailureReason.TIMEOUT,
                    "OpenAI 호출 시간이 초과되었습니다.",
                    exception
            );
        } catch (RestClientResponseException exception) {
            throw new AiGatewayException(
                    exception.getStatusCode().value() == 429
                            || exception.getStatusCode().is5xxServerError()
                            ? AiGatewayFailureReason.TEMPORARY_UNAVAILABLE
                            : AiGatewayFailureReason.INVALID_RESPONSE,
                    "OpenAI API 호출에 실패했습니다. HTTP " + exception.getStatusCode().value(),
                    exception
            );
        } catch (JacksonException exception) {
            throw new AiGatewayException(
                    AiGatewayFailureReason.INVALID_RESPONSE,
                    "OpenAI 응답 JSON을 해석할 수 없습니다.",
                    exception
            );
        }
    }

    private Map<String, Object> initialRequest(AiAnalysisRequest request) {
        Map<String, Object> body = commonRequest();
        body.put("input", List.of(userInput(request)));
        return body;
    }

    private Map<String, Object> userInput(AiAnalysisRequest request) {
        String content = """
                축종: %s
                사용자 지역 제공 여부: %s
                사용자 좌표 제공 여부: %s
                병원 검색 가능 위치 제공 여부: %s
                증상: %s
                """.formatted(
                request.species().name(),
                request.hasRegion(),
                request.hasCoordinates(),
                request.hasRegion() || request.hasCoordinates(),
                request.symptomText()
        );
        return Map.of("role", "user", "content", content);
    }

    private Map<String, Object> followUpRequest(
            AiAnalysisRequest request,
            JsonNode firstResponse,
            String callId,
            String output
    ) {
        if (callId.isBlank()) {
            throw invalidResponse("Tool 호출 연결 식별자가 누락되었습니다.");
        }
        List<Object> input = new ArrayList<>();
        // store=false이므로 이전 응답 ID에 의존하지 않고 대화 연결에 필요한 항목을 직접 다시 구성한다.
        input.add(userInput(request));
        firstResponse.path("output").forEach(input::add);
        input.add(Map.of(
                "type", "function_call_output",
                "call_id", callId,
                "output", output
        ));
        Map<String, Object> body = commonRequest();
        body.put("input", input);
        return body;
    }

    private Map<String, Object> commonRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        // 증상 데이터와 Tool 결과를 OpenAI의 저장 상태에 남기거나 후속 요청에서 참조하지 않는다.
        body.put("store", false);
        body.put("instructions", instructions);
        body.put("tools", List.of(searchToolSchema()));
        body.put("tool_choice", "auto");
        // 서버 구현은 병원 검색 한 번만 처리하므로 병렬 Tool Call 생성을 금지한다.
        body.put("parallel_tool_calls", false);
        body.put("text", Map.of("format", finalOutputFormat()));
        return body;
    }

    private Map<String, Object> searchToolSchema() {
        // Tool 호출 시점의 분석과 최종 응답의 분석을 비교할 수 있도록 공통 분석 5필드도 Tool 인자에 포함한다.
        Map<String, Object> schemaProperties = new LinkedHashMap<>(analysisProperties());
        schemaProperties.put("emergency", nullableBoolean("응급 병원 검색 의도"));
        schemaProperties.put("nightCare", nullableBoolean("명시적인 야간 진료 또는 24시간 병원 의도"));
        schemaProperties.put("openNow", nullableBoolean("현재 방문 가능한 병원 검색 의도"));
        schemaProperties.put("sort", Map.of(
                "type", List.of("string", "null"),
                "enum", Arrays.asList("NAME", "DISTANCE", null),
                "description", "거리 요청이고 사용자 좌표가 제공된 경우에만 DISTANCE"
        ));
        return Map.of(
                "type", "function",
                "name", SEARCH_TOOL_NAME,
                "description", "DoctorPet 데이터베이스에서 조건에 맞는 실제 동물병원을 검색합니다.",
                "parameters", objectSchema(schemaProperties),
                "strict", true
        );
    }

    private Map<String, Object> finalOutputFormat() {
        Map<String, Object> schemaProperties = new LinkedHashMap<>(analysisProperties());
        schemaProperties.put("locationRequired", Map.of("type", "boolean"));
        schemaProperties.put("recommendations", recommendationArray());
        return Map.of(
                "type", "json_schema",
                "name", "doctorpet_ai_consultation",
                "strict", true,
                "schema", objectSchema(schemaProperties)
        );
    }

    private Map<String, Object> recommendationArray() {
        Map<String, Object> recommendationProperties = new LinkedHashMap<>();
        recommendationProperties.put("hospitalId", Map.of("type", "integer"));
        recommendationProperties.put("recommendationScore", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 5
        ));
        Map<String, Object> evidenceProperties = new LinkedHashMap<>();
        evidenceProperties.put("type", Map.of(
                "type", "string",
                "enum", Arrays.stream(AiRecommendationEvidenceType.values())
                        .map(Enum::name)
                        .toList()
        ));
        evidenceProperties.put("value", Map.of("type", "string"));
        recommendationProperties.put("evidence", Map.of(
                "type", "array",
                "items", objectSchema(evidenceProperties)
        ));
        return Map.of(
                "type", "array",
                "maxItems", 3,
                "items", objectSchema(recommendationProperties),
                "description", "Tool 결과에 포함된 병원 중 최대 3개의 추천 결과"
        );
    }

    private Map<String, Object> analysisProperties() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("possibleFocusAreas", enumArray(
                AiFocusArea.names(),
                "진단명이 아닌 증상 관찰 범위"
        ));
        fields.put("requiredCapabilities", capabilityArray());
        fields.put("urgencyLevel", Map.of("type", "string", "enum", List.of("LOW", "MODERATE", "HIGH")));
        fields.put("preVisitCheckpoints", enumArray(
                AiPreVisitCheckpoint.names(),
                "약물이나 처치 지시가 아닌 보호자 관찰 항목"
        ));
        fields.put("recommendVetVisit", Map.of("type", "boolean"));
        return fields;
    }

    private Map<String, Object> objectSchema(Map<String, Object> schemaProperties) {
        // OpenAI strict schema 규칙에 맞춰 nullable 필드도 이름 자체는 항상 존재하게 하고 임의 필드는 금지한다.
        return Map.of(
                "type", "object",
                "properties", schemaProperties,
                "required", new ArrayList<>(schemaProperties.keySet()),
                "additionalProperties", false
        );
    }

    private Map<String, Object> enumArray(List<String> values, String description) {
        return Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", values),
                "description", description
        );
    }

    private Map<String, Object> capabilityArray() {
        List<String> capabilities = Arrays.stream(CapabilityValue.values())
                .filter(value -> value.getType() != CapabilityType.SPECIES)
                .map(CapabilityValue::name)
                .toList();
        return Map.of(
                "type", "array",
                "items", Map.of("type", "string", "enum", capabilities),
                "description", "사용자가 요청한 검사·진료·장비와 일치하는 허용 진료역량"
        );
    }

    private Map<String, Object> nullableBoolean(String description) {
        return Map.of("type", List.of("boolean", "null"), "description", description);
    }

    private List<JsonNode> functionCalls(JsonNode response) {
        List<JsonNode> calls = new ArrayList<>();
        response.path("output").forEach(item -> {
            if ("function_call".equals(item.path("type").asText())) {
                calls.add(item);
            }
        });
        return calls;
    }

    private OpenAiFinalOutput parseFinalOutput(JsonNode response) {
        // Responses API output에는 function_call 등 다른 항목도 섞일 수 있어 최종 message/output_text만 찾는다.
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    try {
                        return objectMapper.readValue(content.path("text").asText(), OpenAiFinalOutput.class);
                    } catch (JacksonException exception) {
                        throw new AiGatewayException(
                                AiGatewayFailureReason.INVALID_RESPONSE,
                                "OpenAI 구조화 응답을 해석할 수 없습니다.",
                                exception
                        );
                    }
                }
            }
        }
        throw invalidResponse("OpenAI 최종 구조화 응답이 없습니다.");
    }

    private OpenAiToolArguments parseToolArguments(String arguments) {
        try {
            return objectMapper.readValue(arguments, OpenAiToolArguments.class);
        } catch (JacksonException exception) {
            throw new AiGatewayException(
                    AiGatewayFailureReason.INVALID_RESPONSE,
                    "OpenAI Tool 인자를 해석할 수 없습니다.",
                    exception
            );
        }
    }

    private AiHospitalSearchToolCall toToolCall(
            OpenAiToolArguments arguments,
            int promptTokens,
            int completionTokens
    ) {
        try {
            return arguments.toToolCall(properties, promptTokens, completionTokens);
        } catch (NullPointerException exception) {
            throw new AiGatewayException(
                    AiGatewayFailureReason.INVALID_RESPONSE,
                    "OpenAI Tool 인자의 필수 필드가 누락되었습니다.",
                    exception
            );
        }
    }

    private int usage(JsonNode response, String field) {
        return response.path("usage").path(field).asInt(0);
    }

    private AiGatewayException invalidResponse(String message) {
        return new AiGatewayException(AiGatewayFailureReason.INVALID_RESPONSE, message);
    }

    private static RestClient buildRestClient(OpenAiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + properties.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    private static String loadInstructions() {
        try {
            return new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("OpenAI 상담 프롬프트를 읽을 수 없습니다: " + PROMPT_PATH, exception);
        }
    }

}
