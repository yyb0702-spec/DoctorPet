package com.doctorpet.domain.reservation.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 예약 승인 타임아웃 배치의 운영 설정. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "reservation.approval-timeout")
public class ReservationApprovalTimeoutProperties {

    @Positive
    private long intervalMs = 60_000L;

    @PositiveOrZero
    private long initialDelayMs = 60_000L;

    @Positive
    private int batchSize = 100;

    /** 최초 시도를 포함한 건별 최대 시도 횟수. */
    @Positive
    private int maxAttempts = 2;

    /** 다른 인스턴스가 실행 중이면 기다리지 않고 다음 주기로 넘기는 것이 기본값이다. */
    @PositiveOrZero
    private int lockWaitSeconds = 0;
}
