package com.doctorpet.domain.payment.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제 알림 발행 기본 배선. 실제 저장 구현(#39)이 없을 때만 로그 기반 no-op 구현을 등록한다.
 * #39가 {@link PaymentNotificationPublisher} 빈을 제공하면 {@code @ConditionalOnMissingBean}에 의해
 * 이 기본 구현은 등록되지 않으므로 빈 충돌 없이 자연스럽게 대체된다.
 */
@Configuration
public class PaymentNotificationConfig {

    @Bean
    @ConditionalOnMissingBean(PaymentNotificationPublisher.class)
    public PaymentNotificationPublisher loggingPaymentNotificationPublisher() {
        return new LoggingPaymentNotificationPublisher();
    }
}
