package com.doctorpet.global.gateway.payment.portone;

import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelResult;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import com.doctorpet.global.gateway.payment.support.SensitiveDataMasker;
import com.doctorpet.global.time.TimePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PortOne V2 실연동 결제 게이트웨이. {@code payment.gateway=portone}일 때만 활성화된다(운영, #57).
 *
 * <p>계약(PortOne V2 REST 공식 문서 기준):
 * <ul>
 *   <li>인증: {@code POST /login/api-secret} 로 API Secret을 액세스 토큰으로 교환 후 {@code Authorization: Bearer}.</li>
 *   <li>빌링키 조회: {@code GET /billing-keys/{billingKey}} → {@code status}(ISSUED/DELETED)·{@code methods[].card}.</li>
 *   <li>빌링키 결제: {@code POST /payments/{paymentId}/billing-key} (paymentId=merchantPaymentId 멱등키).</li>
 *   <li>결제 조회: {@code GET /payments/{paymentId}} → {@code status}·{@code amount.total}.</li>
 * </ul>
 * 응답 JSON은 방어적으로 파싱한다(필드 경로가 채널·버전에 따라 미세하게 달라도 견디도록). 재시도 루프·상태
 * 전이·금액 대조는 이 계약의 책임이 아니라 상위 결제 서비스가 담당한다(SA §9-4). 오류 코드 → 재시도 성격
 * 매핑은 {@link PortOneErrorCodeMapper}가 한다. 인증정보·빌링키 원본은 로그에 남기지 않는다(마스킹, 보안).
 */
@Slf4j
@Component
@EnableConfigurationProperties(PortOneProperties.class)
@ConditionalOnProperty(name = "payment.gateway", havingValue = "portone")
public class PortOnePaymentGateway implements PaymentGateway {

    private static final String CURRENCY_KRW = "KRW";

    private final PortOneProperties properties;
    private final PortOneErrorCodeMapper errorCodeMapper;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // 생성자가 둘이라 스프링이 자동 선택하지 못하므로, 운영 배선에 쓸 이 생성자를 @Autowired로 명시한다
    // (없으면 gateway=portone일 때 "No default constructor found"로 컨텍스트 로딩 실패 — 실 e2e에서 확인, #57).
    @Autowired
    public PortOnePaymentGateway(PortOneProperties properties, PortOneErrorCodeMapper errorCodeMapper) {
        this(properties, errorCodeMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                        .build(),
                new ObjectMapper());
    }

    // 테스트에서 HttpClient·ObjectMapper를 주입해 실제 네트워크 없이 요청/응답 바인딩을 검증하기 위한 생성자.
    PortOnePaymentGateway(PortOneProperties properties, PortOneErrorCodeMapper errorCodeMapper,
                          HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.errorCodeMapper = errorCodeMapper;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        requireConfigured();
    }

    @Override
    public BillingKeyIssueResult verifyBillingKey(String billingKey) {
        log.debug("PortOne verifyBillingKey 요청 billingKey={}", SensitiveDataMasker.maskSecret(billingKey));
        String token = issueAccessToken();
        HttpResponse<String> response = send(
                authorized("/billing-keys/" + encodePathSegment(billingKey), token).GET().build(), "verifyBillingKey");
        // 존재하지 않는 빌링키는 예외가 아니라 '유효하지 않음'으로 상위(등록 400)에 알린다.
        if (response.statusCode() == 404) {
            return new BillingKeyIssueResult(false, null, null);
        }
        JsonNode root = ensureSuccess(response, null, "verifyBillingKey");
        if (!"ISSUED".equalsIgnoreCase(text(root, "status"))) {
            // DELETED 등 사용 불가 상태.
            return new BillingKeyIssueResult(false, null, null);
        }
        JsonNode card = firstCard(root);
        String brand = card != null ? text(card, "brand") : null;
        String last4 = card != null ? last4(text(card, "number")) : null;
        // PortOne 빌링키 조회 응답의 issueId는 SDK 발급 요청 때 우리가 넘긴 발급 건별 ID다.
        // (merchantId는 고객사 상수 ID라 발급 건 식별에 못 쓴다 — issueId를 읽어야 콜백을
        // 특정 발급 시도에 귀속할 수 있다.) 빌링키 원문과 달리 응답·로그에 노출하지 않는다.
        return new BillingKeyIssueResult(true, brand, last4, text(root, "issueId"));
    }

    @Override
    public PaymentApproveResult approve(PaymentApproveCommand command) {
        // 멱등키는 로깅해 추적하되, 빌링키 원본은 마스킹한다(보안 규칙).
        log.info("PortOne approve 요청 merchantPaymentId={} amount={} billingKey={}",
                command.merchantPaymentId(), command.amount(), SensitiveDataMasker.maskSecret(command.billingKey()));
        String token = issueAccessToken();
        String body = writeJson(buildBillingKeyPaymentBody(command));
        HttpResponse<String> response = send(
                authorized("/payments/" + encodePathSegment(command.merchantPaymentId()) + "/billing-key", token)
                        .header("Content-Type", "application/json")
                        // PortOne V2 멱등 키. 통신 오류 후 상위가 같은 merchantPaymentId로 승인을 재요청해도
                        // PortOne이 기존 요청 결과를 반환해(진행 중이면 409 IDEMPOTENCY_OUTSTANDING_REQUEST) 이중
                        // 승인을 막는다. 최초·재시도가 반드시 같은 값이어야 하므로 merchantPaymentId를 쓴다.
                        .header("Idempotency-Key", idempotencyKey(command.merchantPaymentId()))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(), "approve");
        JsonNode root = ensureSuccess(response, command.merchantPaymentId(), "approve");
        JsonNode payment = root.has("payment") ? root.get("payment") : root;
        return new PaymentApproveResult(
                mapStatus(text(payment, "status")),
                text(payment, "pgTxId", "transactionId", "id"),
                totalAmount(payment),
                parseDateTime(text(payment, "paidAt")));
    }

    @Override
    public PaymentQueryResult query(String merchantPaymentId) {
        log.info("PortOne query 요청 merchantPaymentId={}", merchantPaymentId);
        String token = issueAccessToken();
        HttpResponse<String> response = send(
                authorized("/payments/" + encodePathSegment(merchantPaymentId), token).GET().build(), "query");
        // PortOne에 결제 기록이 없으면 승인 미도달로 간주(미확정). 상위가 PENDING 유지·정산(#35)에 맡긴다 —
        // 오프라인 이중수납을 막기 위해 FAILED로 단정하지 않는다.
        if (response.statusCode() == 404) {
            return new PaymentQueryResult(GatewayPaymentStatus.PENDING, null, 0);
        }
        JsonNode payment = ensureSuccess(response, merchantPaymentId, "query");
        GatewayPaymentStatus status = mapStatus(text(payment, "status"));
        int paidAmount = status == GatewayPaymentStatus.PAID ? totalAmount(payment) : 0;
        return new PaymentQueryResult(status, text(payment, "pgTxId", "transactionId", "id"), paidAmount);
    }

    /**
     * 결제를 전액 취소한다(#37). {@code POST /payments/{paymentId}/cancel}, 멱등키는 {@code merchantRefundId}다.
     *
     * <p>이미 전액 취소된 결제에 재요청하면 PortOne이 취소 불가 오류를 주는데, 이를 실패로 올리면 상위의
     * 재시도·복구 경로가 영구히 실패한다. 그래서 {@link PortOneErrorCodeMapper#isAlreadyCancelled}로 판별해
     * 단건조회로 실제 취소 상태를 확인한 뒤 성공(기존 취소 결과)으로 흡수한다 — 계약이 약속한 재요청 동작이다.
     */
    @Override
    public PaymentCancelResult cancel(PaymentCancelCommand command) {
        log.info("PortOne cancel 요청 merchantPaymentId={} merchantRefundId={} amount={}",
                command.merchantPaymentId(), command.merchantRefundId(), command.amount());
        String token = issueAccessToken();
        String body = writeJson(buildCancelBody(command));
        HttpResponse<String> response = send(
                authorized("/payments/" + encodePathSegment(command.merchantPaymentId()) + "/cancel", token)
                        .header("Content-Type", "application/json")
                        // 결제 승인과 다른 별도 멱등키. 통신 오류 후 같은 merchantRefundId로 재요청하면
                        // PortOne이 기존 취소 결과를 반환해 이중 취소를 막는다.
                        .header("Idempotency-Key", idempotencyKey(command.merchantRefundId()))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(), "cancel");

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            JsonNode error = readTree(response.body());
            String providerCode = text(error, "type", "code");
            if (errorCodeMapper.isAlreadyCancelled(providerCode)) {
                // 이미 취소됨 → 단건조회로 확인한 뒤 성공으로 흡수한다(재시도 안전성).
                log.info("PortOne cancel — 이미 취소된 결제로 응답, 조회로 확인 후 성공 처리: merchantPaymentId={} providerCode={}",
                        command.merchantPaymentId(), providerCode);
                return confirmCancelledByQuery(command, token);
            }
            throw translate(providerCode, response.statusCode(),
                    "PortOne cancel 실패 status=" + response.statusCode()
                            + " merchantPaymentId=" + command.merchantPaymentId(), null);
        }

        JsonNode root = readTree(response.body());
        JsonNode cancellation = firstCancellation(root);
        if (cancellation == null) {
            // 2xx인데 취소 내역이 없다 — 취소 성립 여부가 불확실하므로 단정하지 않고 UNKNOWN으로 올려
            // 상위가 같은 멱등키 재시도로 확정하게 한다(성공으로 오판하면 환불 안 된 건이 REFUNDED가 된다).
            throw new PaymentGatewayException(GatewayFailureReason.UNKNOWN, null,
                    "PortOne cancel 응답에 취소 내역이 없습니다. merchantPaymentId=" + command.merchantPaymentId(), null);
        }
        return toCancelResult(cancellation);
    }

    /**
     * "이미 취소됨" 응답을 단건조회로 확인해 취소 결과로 환산한다. 취소 내역(취소 금액)을 확인할 수 있을 때만
     * 성공으로 환산하고, 그러지 못하면 UNKNOWN으로 올려 상위가 재시도·운영 확인으로 넘기게 한다.
     *
     * <p>결제 상태만으로 전액 취소를 단정하지 않는다(PR #112 리뷰 P1) — {@code mapStatus}는 {@code CANCELLED}와
     * {@code PARTIAL_CANCELLED}를 모두 {@code FAILED}로 합치므로 상태만으로는 부분 취소를 구분할 수 없다.
     * 요청 금액을 그대로 채워 넣으면 상위 금액 대조가 통과해 일부만 취소된 결제가 전액 환불로 확정된다.
     */
    private PaymentCancelResult confirmCancelledByQuery(PaymentCancelCommand command, String token) {
        HttpResponse<String> response = send(
                authorized("/payments/" + encodePathSegment(command.merchantPaymentId()), token).GET().build(),
                "cancel-confirm");
        JsonNode payment = ensureSuccess(response, command.merchantPaymentId(), "cancel-confirm");
        JsonNode cancellation = firstCancellation(payment);
        if (cancellation != null) {
            return toCancelResult(cancellation);
        }
        throw new PaymentGatewayException(GatewayFailureReason.UNKNOWN, null,
                "PortOne cancel 재요청이 이미 취소됨으로 응답했으나 조회에서 취소 내역(취소 금액)을 확인할 수 "
                        + "없습니다. 전액 취소로 단정하지 않습니다. merchantPaymentId="
                        + command.merchantPaymentId(), null);
    }

    private PaymentCancelResult toCancelResult(JsonNode cancellation) {
        return new PaymentCancelResult(
                text(cancellation, "pgCancellationId", "id"),
                cancelledAmount(cancellation),
                parseDateTime(text(cancellation, "cancelledAt")));
    }

    // --- 요청/응답 헬퍼 ---

    /**
     * API Secret을 액세스 토큰으로 교환한다(PortOne V2는 토큰 인증만 지원). 토큰은 만료가 있으나 MVP에선
     * 호출마다 발급한다 — 캐싱은 만료 관리 복잡도가 있어 후속 최적화로 둔다.
     */
    private String issueAccessToken() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("apiSecret", properties.getApiSecret());
        HttpResponse<String> response = send(
                unauthorized("/login/api-secret")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                        .build(), "login");
        JsonNode root = ensureSuccess(response, null, "login");
        String token = text(root, "accessToken");
        if (isBlank(token)) {
            throw translate(null, response.statusCode(), "PortOne 토큰 응답에 accessToken이 없습니다.", null);
        }
        return token;
    }

    private ObjectNode buildBillingKeyPaymentBody(PaymentApproveCommand command) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("storeId", properties.getStoreId());
        if (!isBlank(properties.getChannelKey())) {
            body.put("channelKey", properties.getChannelKey());
        }
        body.put("billingKey", command.billingKey());
        body.put("orderName", command.orderName());
        body.put("currency", CURRENCY_KRW);
        body.putObject("amount").put("total", command.amount());
        return body;
    }

    private ObjectNode buildCancelBody(PaymentCancelCommand command) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("storeId", properties.getStoreId());
        // 전액 취소지만 금액을 명시한다 — 서버가 의도한 금액과 다른 취소가 성립하면 상위 금액 대조에서 걸린다.
        body.put("amount", command.amount());
        body.put("reason", command.reason());
        return body;
    }

    /** 취소 응답에서 취소 내역을 찾는다(cancellation → cancellations[0] 순). 부분 취소 확장 시 재검토 지점이다. */
    private JsonNode firstCancellation(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode single = root.get("cancellation");
        if (single != null && !single.isNull()) {
            return single;
        }
        JsonNode list = root.get("cancellations");
        if (list != null && list.isArray() && !list.isEmpty()) {
            return list.get(0);
        }
        return null;
    }

    /** 취소 내역의 취소 금액. totalAmount와 달리 PortOne 취소 내역은 amount가 평면 숫자로 온다. */
    private int cancelledAmount(JsonNode cancellation) {
        JsonNode amount = cancellation.get("totalAmount");
        if (amount != null && amount.isNumber()) {
            return amount.intValue();
        }
        return totalAmount(cancellation);
    }

    private HttpRequest.Builder authorized(String path, String token) {
        return unauthorized(path).header("Authorization", "Bearer " + token);
    }

    private HttpRequest.Builder unauthorized(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.getBaseUrl() + path))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()));
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 승인 도달 여부 불명 → UNKNOWN(상위가 조회 우선, 무분별 재시도 금지).
            throw new PaymentGatewayException(GatewayFailureReason.UNKNOWN, null, "PortOne 통신 중단: " + operation, e);
        } catch (IOException e) {
            // 네트워크 오류 → 재시도 유효(RETRIABLE).
            throw new PaymentGatewayException(GatewayFailureReason.RETRIABLE, null, "PortOne 통신 실패: " + operation, e);
        }
    }

    private JsonNode ensureSuccess(HttpResponse<String> response, String merchantPaymentId, String operation) {
        JsonNode root = readTree(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String providerCode = text(root, "type", "code");
            throw translate(providerCode, response.statusCode(),
                    "PortOne " + operation + " 실패 status=" + response.statusCode()
                            + (merchantPaymentId != null ? " merchantPaymentId=" + merchantPaymentId : ""), null);
        }
        return root != null ? root : objectMapper.createObjectNode();
    }

    /**
     * 공급자 예외를 내부 실패 사유로 분류해 게이트웨이 예외로 변환한다. 공급자 코드로 먼저 분류하고,
     * 인식하지 못하면(UNKNOWN) HTTP 상태로 보조 분류한다(5xx·408·429는 재시도 유효).
     */
    PaymentGatewayException translate(String providerErrorCode, Integer httpStatus, String message, Throwable cause) {
        GatewayFailureReason reason = errorCodeMapper.classify(providerErrorCode);
        if (reason == GatewayFailureReason.UNKNOWN && httpStatus != null) {
            reason = errorCodeMapper.classifyHttpStatus(httpStatus);
        }
        log.warn("PortOne 오류 분류 providerCode={} httpStatus={} reason={}", providerErrorCode, httpStatus, reason);
        return new PaymentGatewayException(reason, providerErrorCode, message, cause);
    }

    // --- JSON 파싱 헬퍼(방어적) ---

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private String text(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    /** 빌링키 조회 응답에서 첫 카드 정보를 찾는다(methods[].card → method.card → card 순, 카드 스냅샷용). */
    private JsonNode firstCard(JsonNode billingKeyInfo) {
        JsonNode methods = billingKeyInfo.get("methods");
        if (methods != null && methods.isArray()) {
            for (JsonNode method : methods) {
                JsonNode card = method.get("card");
                if (card != null && !card.isNull()) {
                    return card;
                }
            }
        }
        JsonNode method = billingKeyInfo.get("method");
        if (method != null && method.get("card") != null && !method.get("card").isNull()) {
            return method.get("card");
        }
        JsonNode card = billingKeyInfo.get("card");
        return (card != null && !card.isNull()) ? card : null;
    }

    private String last4(String maskedNumber) {
        if (maskedNumber == null) {
            return null;
        }
        String digits = maskedNumber.replaceAll("\\D", "");
        return digits.length() >= 4 ? digits.substring(digits.length() - 4) : null;
    }

    private int totalAmount(JsonNode payment) {
        JsonNode amount = payment.get("amount");
        if (amount != null) {
            JsonNode total = amount.get("total");
            if (total != null && total.isNumber()) {
                return total.intValue();
            }
            if (amount.isNumber()) {
                return amount.intValue();
            }
        }
        return 0;
    }

    /** PortOne 결제 상태 → 공급자 중립 상태. 미인식/미확정은 PENDING으로 안전 분류한다. */
    private GatewayPaymentStatus mapStatus(String status) {
        if (status == null) {
            return GatewayPaymentStatus.PENDING;
        }
        return switch (status.toUpperCase()) {
            case "PAID" -> GatewayPaymentStatus.PAID;
            case "FAILED", "CANCELLED", "PARTIAL_CANCELLED" -> GatewayPaymentStatus.FAILED;
            default -> GatewayPaymentStatus.PENDING; // READY, PAY_PENDING, VIRTUAL_ACCOUNT_ISSUED 등
        };
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            // offset을 버리지 않고 같은 순간의 서울 시각으로 변환한다(공통 시간 정책). 예: 01:00Z → 10:00.
            // toLocalDateTime()만 쓰면 UTC 응답이 그대로 저장돼 9시간 틀어진다(PR #95 리뷰 반영).
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(TimePolicy.SEOUL_ZONE_ID)
                    .toLocalDateTime();
        } catch (RuntimeException e) {
            try {
                return LocalDateTime.parse(value);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new PaymentGatewayException(GatewayFailureReason.UNKNOWN, null, "PortOne 요청 직렬화 실패", e);
        }
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * PortOne 멱등 키를 만든다. 최초 승인과 통신 오류 후 재시도가 반드시 같은 값이어야 이중 승인을 막을 수
     * 있으므로, 결제당 유일한 merchantPaymentId를 키로 쓴다.
     *
     * <p>PortOne V2는 이 헤더 값을 RFC 8941 Structured Fields의 String으로 해석하므로 반드시 쌍따옴표로 감싼
     * 형태({@code "<값>"})여야 한다 — raw 값으로 보내면 파싱에 실패해 멱등 처리가 적용되지 않거나 요청이
     * 거부될 수 있다(PR #95 리뷰 반영). 승인은 merchantPaymentId({@code pay_}+UUID hex 36자), 취소는
     * merchantRefundId({@code rfd_}+UUID hex 36자)라 항상 16~256자 ASCII이고 이스케이프가 필요한
     * 문자(따옴표·역슬래시)를 포함하지 않으므로 그대로 감싼다.
     */
    private String idempotencyKey(String key) {
        if (isBlank(key)) {
            throw new PaymentGatewayException(GatewayFailureReason.UNKNOWN, null,
                    "PortOne 요청에 멱등 키가 없습니다.", null);
        }
        return "\"" + key + "\"";
    }

    private void requireConfigured() {
        // channel-key는 빌링키 결제 body에 실려야 하는 필수값이다. 빠지면 기동은 성공하고 실제 결제
        // 시점에만 실패하므로(운영 장애) base-url·api-secret·store-id와 함께 기동 시점에 막는다(PR #95 리뷰 반영).
        if (isBlank(properties.getBaseUrl())
                || isBlank(properties.getApiSecret())
                || isBlank(properties.getStoreId())
                || isBlank(properties.getChannelKey())) {
            throw new IllegalStateException(
                    "PortOne 실연동 설정이 없습니다. payment.portone.base-url·api-secret·store-id·channel-key를 "
                            + "모두 주입하세요(커밋 금지).");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
