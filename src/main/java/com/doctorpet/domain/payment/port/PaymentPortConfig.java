package com.doctorpet.domain.payment.port;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제 도메인 port 기본 배선. 예약 도메인(#27)이 실제 {@link ReservationLookupPort} 구현을 제공하기 전까지
 * 컨텍스트 부팅을 위해 임시 기본 구현({@link UnwiredReservationLookupPort})을 등록한다.
 * #27이 구현 빈을 제공하면 {@code @ConditionalOnMissingBean}에 의해 이 기본 구현은 등록되지 않는다.
 */
@Configuration
public class PaymentPortConfig {

    @Bean
    @ConditionalOnMissingBean(ReservationLookupPort.class)
    public ReservationLookupPort unwiredReservationLookupPort() {
        return new UnwiredReservationLookupPort();
    }
}
