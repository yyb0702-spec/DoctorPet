package com.doctorpet.domain.payment.config;

// 결제 정산 스케줄러 설정(#35, SA §9-7). 잘못된 값(음수 임계·0 배치 등)이 런타임에야 터지지 않도록
// @Validated로 애플리케이션 시작 시점에 거부한다(PR #81 P2 리뷰 반영).

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "payment.reconcile")
public class PaymentReconcileProperties {

    // 이 시간 이상 PENDING으로 머문 결제만 정산 대상으로 본다(유예). 0은 유예 없음(즉시 대상)으로 유효하고,
    // 음수는 임계를 미래로 만들어 방금 생성된 PENDING까지 대상에 포함시키므로 금지한다.
    @PositiveOrZero
    private long staleAfterMs = 120_000L;

    // 한 배치에서 처리할 최대 건수. 0이면 PageRequest.of(0, 0)이 실패하므로 1 이상이어야 한다.
    @Positive
    private int batchSize = 100;

    // 재조회 재시도 상한(운영 알림 임계). 0이면 첫 조회부터 RECONCILE_STUCK으로 분류되므로 1 이상이어야 한다.
    @Positive
    private int maxAttempts = 10;

    // 정산 배치 실행 주기(ms). 스케줄러가 fixedDelay로 사용한다. 0·음수는 스케줄링이 성립하지 않으므로 금지한다.
    @Positive
    private long intervalMs = 300_000L;
}
