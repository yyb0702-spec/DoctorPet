package com.doctorpet.domain.payment.config;

// 진료비 청구 재시도 설정(#85 데드라인 캡, SA §9-4). 잘못된 값(0·음수 데드라인)이 런타임에야 드러나지 않도록
// @Validated로 애플리케이션 시작 시점에 거부한다(PR #92 P2 리뷰 반영, PaymentReconcileProperties와 동일 패턴).

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "payment.charge")
public class PaymentChargeProperties {

    // 재시도 백오프 누적 대기의 상한(ms, #85). HTTP 요청 스레드가 지수 백오프로 과도하게 점유돼 스레드풀이
    // 고갈되는 것을 막는다. 0·음수면 remainingBudgetMs <= 0 분기에서 모든 재시도가 조용히 비활성화되고
    // 최종 단건조회로 바로 넘어가 운영 설정 오류를 장애 전에는 알 수 없으므로 1 이상이어야 한다.
    // 기본값 3500ms는 기본 백오프 3회 누적(500+1000+2000)과 같아 기본 동작을 바꾸지 않는다.
    @Positive
    private long retryBackoffDeadlineMs = 3500L;
}
