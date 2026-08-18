package com.doctorpet.domain.payment.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Level 1 — 청구 감사 로그 출력 검증(#84). 로그 파이프라인이 선별할 AUDIT 마커와 누가·얼마·어느 예약 필드를
 * 담는지, 그리고 민감정보(빌링키 등)를 남기지 않는지 실제 로그 이벤트로 확인한다.
 */
class Slf4jPaymentChargeAuditLoggerTest {

    private final Slf4jPaymentChargeAuditLogger auditLogger = new Slf4jPaymentChargeAuditLogger();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(Slf4jPaymentChargeAuditLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("AUDIT 마커와 채널·행위자·예약·결제·금액을 한 줄로 남긴다")
    void recordsAuditLine() {
        auditLogger.recordChargeAccepted(PaymentChargeChannel.STAFF_CHARGE, 9L, 100L, 1L, 50_000);

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("AUDIT")
                .contains("channel=STAFF_CHARGE")
                .contains("actorMemberId=9")
                .contains("reservationId=100")
                .contains("paymentId=1")
                .contains("amount=50000");
    }

    @Test
    @DisplayName("보호자 셀프 복구는 GUARDIAN_RECOVERY 채널로 남겨 스태프 청구와 구분된다")
    void recordsGuardianRecoveryChannel() {
        auditLogger.recordChargeAccepted(PaymentChargeChannel.GUARDIAN_RECOVERY, 51L, 100L, 2L, 50_000);

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message)
                .contains("channel=GUARDIAN_RECOVERY")
                .contains("actorMemberId=51");
    }

    @Test
    @DisplayName("감사 로그에 민감정보(빌링키·카드번호)를 남기지 않는다")
    void doesNotLogSensitiveData() {
        auditLogger.recordChargeAccepted(PaymentChargeChannel.STAFF_CHARGE, 9L, 100L, 1L, 50_000);

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message.toLowerCase())
                .doesNotContain("billing")
                .doesNotContain("card")
                .doesNotContain("enc");
    }
}
