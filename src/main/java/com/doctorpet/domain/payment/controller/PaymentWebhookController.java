package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.webhook.PaymentWebhookService;
import com.doctorpet.domain.payment.webhook.PortOneWebhookVerifier;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/*
  PortOne 결제 웹훅 수신 API(SA §8-7·§4 payment_webhooks, 이슈 #48). POST /api/payments/webhook.
  인증은 JWT가 아니라 웹훅 서명 검증이다(SecurityConfig에서 permitAll). 서명은 원문 body 기준이라 @RequestBody를
  String으로 받아 파싱 전에 검증한다 — 역직렬화된 DTO로는 원문 바이트가 달라져 서명이 어긋난다.
  검증 실패는 401로 거부하고, 통과하면 (event_type, merchantPaymentId)만 뽑아 서비스에 넘긴다. 상태 전이는
  서비스가 재조회로 멱등 확정한다(웹훅=트리거). 처리 실패해도 결제는 #35 정산 스케줄러가 백업 확정한다.
 */
@Slf4j
@RestController
public class PaymentWebhookController {

    // 웹훅 body 파싱 전용. 특별한 설정이 필요 없고, MVC 메시지 컨버터의 ObjectMapper 빈에 의존하지 않도록
    // 자체 인스턴스를 둔다(스레드 안전, 재사용).
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PortOneWebhookVerifier webhookVerifier;
    private final PaymentWebhookService webhookService;

    public PaymentWebhookController(
            PortOneWebhookVerifier webhookVerifier,
            PaymentWebhookService webhookService
    ) {
        this.webhookVerifier = webhookVerifier;
        this.webhookService = webhookService;
    }

    @PostMapping("/api/payments/webhook")
    public ResponseEntity<ApiResponse<Void>> receive(
            @RequestHeader(name = "webhook-id", required = false) String webhookId,
            @RequestHeader(name = "webhook-timestamp", required = false) String webhookTimestamp,
            @RequestHeader(name = "webhook-signature", required = false) String webhookSignature,
            @RequestBody(required = false) String body
    ) {
        if (!webhookVerifier.verify(webhookId, webhookTimestamp, webhookSignature, body)) {
            throw new ServiceException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        WebhookEvent event = parse(body);
        webhookService.handle(event.eventType(), event.merchantPaymentId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    /**
     * 검증을 통과한 body에서 이벤트 타입과 결제 식별자만 뽑는다. PortOne V2 웹훅은 {@code type}과
     * {@code data.paymentId}(= 서버가 만든 merchantPaymentId)를 담는다. 파싱 실패·필드 누락은 null로 두어
     * 서비스가 '무시'로 처리하게 한다(민감정보·원문 오류는 로그에 남기지 않는다).
     */
    private WebhookEvent parse(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            String eventType = text(root, "type");
            JsonNode data = root.get("data");
            String merchantPaymentId = data != null ? text(data, "paymentId") : null;
            return new WebhookEvent(eventType, merchantPaymentId);
        } catch (Exception e) {
            log.warn("결제 웹훅 본문 파싱 실패 — 무시");
            return new WebhookEvent(null, null);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull() && !value.asText().isBlank()) ? value.asText() : null;
    }

    private record WebhookEvent(String eventType, String merchantPaymentId) {
    }
}
