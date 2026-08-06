package com.doctorpet.domain.payment.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Level 1 — PortOne 웹훅 서명 검증(#48, Standard Webhooks). 정상 서명 통과와, 헤더 누락·시크릿 미설정·
 * 타임스탬프 만료·body 변조·버전 불일치·서명 불일치를 모두 거부하는지 확인한다.
 */
class PortOneWebhookVerifierTest {

    // whsec_ + base64(32바이트 키). 실제 PortOne 시크릿과 동일한 형식(테스트용 고정값).
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String TIMESTAMP = String.valueOf(NOW.getEpochSecond());
    private static final String WEBHOOK_ID = "wh_1";
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay_1\"}}";

    private final PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(SECRET, CLOCK);

    /** 검증기와 동일한 방식(HMAC-SHA256(base64키, "{id}.{ts}.{body}") → base64)으로 기대 서명을 만든다. */
    private static String sign(String id, String timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        byte[] digest = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String header(String signature) {
        return "v1," + signature;
    }

    @Test
    @DisplayName("정상 서명은 통과한다")
    void validSignature() throws Exception {
        assertThat(verifier.verify(WEBHOOK_ID, TIMESTAMP, header(sign(WEBHOOK_ID, TIMESTAMP, BODY)), BODY)).isTrue();
    }

    @Test
    @DisplayName("헤더(webhook-id 등)가 누락되면 거부한다")
    void missingHeaders() throws Exception {
        String validSig = header(sign(WEBHOOK_ID, TIMESTAMP, BODY));
        assertThat(verifier.verify(null, TIMESTAMP, validSig, BODY)).isFalse();
        assertThat(verifier.verify(WEBHOOK_ID, null, validSig, BODY)).isFalse();
        assertThat(verifier.verify(WEBHOOK_ID, TIMESTAMP, null, BODY)).isFalse();
        assertThat(verifier.verify(WEBHOOK_ID, TIMESTAMP, validSig, null)).isFalse();
    }

    @Test
    @DisplayName("시크릿이 설정되지 않으면 모든 웹훅을 거부한다(fail-safe)")
    void blankSecret() throws Exception {
        PortOneWebhookVerifier noSecret = new PortOneWebhookVerifier("", CLOCK);
        assertThat(noSecret.verify(WEBHOOK_ID, TIMESTAMP, header(sign(WEBHOOK_ID, TIMESTAMP, BODY)), BODY)).isFalse();
    }

    @Test
    @DisplayName("타임스탬프가 허용오차(5분)를 벗어나면 거부한다(재전송 방지)")
    void expiredTimestamp() throws Exception {
        String oldTimestamp = String.valueOf(NOW.getEpochSecond() - 600); // 10분 전
        assertThat(verifier.verify(WEBHOOK_ID, oldTimestamp, header(sign(WEBHOOK_ID, oldTimestamp, BODY)), BODY))
                .isFalse();
    }

    @Test
    @DisplayName("body가 변조되면 서명이 어긋나 거부한다")
    void tamperedBody() throws Exception {
        String signatureForOriginal = header(sign(WEBHOOK_ID, TIMESTAMP, BODY));
        String tampered = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"pay_ATTACKER\"}}";
        assertThat(verifier.verify(WEBHOOK_ID, TIMESTAMP, signatureForOriginal, tampered)).isFalse();
    }

    @Test
    @DisplayName("지원하지 않는 서명 버전(v2 등)은 무시하고 거부한다")
    void wrongVersion() throws Exception {
        assertThat(verifier.verify(WEBHOOK_ID, TIMESTAMP, "v2," + sign(WEBHOOK_ID, TIMESTAMP, BODY), BODY)).isFalse();
    }

    @Test
    @DisplayName("여러 서명 중 하나라도 일치하면 통과한다(키 롤오버 대비)")
    void multipleSignatures_oneMatches() throws Exception {
        String signatureHeader = "v1,invalidsignature " + header(sign(WEBHOOK_ID, TIMESTAMP, BODY));
        assertThat(verifier.verify(WEBHOOK_ID, TIMESTAMP, signatureHeader, BODY)).isTrue();
    }
}
