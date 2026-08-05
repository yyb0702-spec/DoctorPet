package com.doctorpet.domain.payment.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
  PortOne 웹훅 서명 검증(이슈 #48). PortOne V2는 Standard Webhooks 규격을 따른다:
  - 헤더: webhook-id, webhook-timestamp, webhook-signature
  - 서명 대상 문자열: "{webhook-id}.{webhook-timestamp}.{body}" (원문 body 그대로)
  - HMAC-SHA256 → base64. 시크릿은 "whsec_" 접두 + base64(키 바이트) 형태다.
  - webhook-signature는 공백 구분 "v1,{base64서명}" 목록이다(키 롤오버 대비 다중 서명 가능).
  타임스탬프 허용오차(재전송 공격 방지)와 상수 시간 비교로 검증한다. 시크릿 미설정 시 모든 웹훅을 거부한다(fail-safe).
 */
@Component
public class PortOneWebhookVerifier {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SECRET_PREFIX = "whsec_";
    private static final String SUPPORTED_VERSION = "v1";
    private static final Duration TIMESTAMP_TOLERANCE = Duration.ofMinutes(5);

    private final String webhookSecret;
    // 타임스탬프 허용오차 판정을 서울 기준 공통 Clock으로 한다(§9-7·v1.23, LocalDateTime.now 직접 호출 금지).
    private final Clock clock;

    public PortOneWebhookVerifier(
            @Value("${payment.portone.webhook-secret:}") String webhookSecret,
            Clock clock
    ) {
        this.webhookSecret = webhookSecret;
        this.clock = clock;
    }

    /**
     * 웹훅 서명을 검증한다. 헤더·body·시크릿 중 하나라도 비었거나, 타임스탬프가 허용오차를 벗어나거나,
     * 어떤 서명도 일치하지 않으면 false를 반환한다(예외를 던지지 않는다 — 호출부가 401로 거부).
     */
    public boolean verify(String webhookId, String webhookTimestamp, String signatureHeader, String body) {
        if (isBlank(webhookSecret) || isBlank(webhookId) || isBlank(webhookTimestamp)
                || isBlank(signatureHeader) || body == null) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(webhookTimestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = Instant.now(clock).getEpochSecond();
        if (Math.abs(now - timestamp) > TIMESTAMP_TOLERANCE.toSeconds()) {
            return false;
        }

        String expected = sign(webhookId + "." + webhookTimestamp + "." + body);
        if (expected == null) {
            return false;
        }
        // webhook-signature는 "v1,sig1 v1,sig2 ..." 형태. v1 서명 중 하나라도 상수 시간 비교로 일치하면 통과.
        for (String entry : signatureHeader.split(" ")) {
            int comma = entry.indexOf(',');
            if (comma < 0) {
                continue;
            }
            if (!SUPPORTED_VERSION.equals(entry.substring(0, comma))) {
                continue;
            }
            String provided = entry.substring(comma + 1);
            if (constantTimeEquals(expected, provided)) {
                return true;
            }
        }
        return false;
    }

    private String sign(String signedContent) {
        try {
            byte[] key = decodeSecret(webhookSecret);
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            // 시크릿 형식 오류 등 → 검증 실패로 처리(거부).
            return null;
        }
    }

    private byte[] decodeSecret(String secret) {
        String base64 = secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret;
        return Base64.getDecoder().decode(base64);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
