package com.doctorpet.domain.payment.config;

// 환불 설정(#37). 잘못된 값이 런타임에야 드러나지 않도록 @Validated로 시작 시점에 거부한다
// (PaymentReconcileProperties와 같은 방식).

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
@ConfigurationProperties(prefix = "payment.refund")
public class PaymentRefundProperties {

    /*
      REQUESTED 선점을 "멈춘 것"으로 보고 회수하기까지의 시간(ms). 이 값보다 오래된 claimed_at만 다른 요청이
      넘겨받는다.
      - 너무 짧으면: 정상적으로 PG 취소 중인 요청을 가로채 같은 멱등키로 동시 호출한다(PortOne이 진행 중
        멱등 요청에 409를 주므로 취소가 두 번 되지는 않지만, 불필요한 실패가 생긴다).
      - 너무 길면: 앱이 죽어 멈춘 환불의 복구가 그만큼 늦어진다(그 사이 결제는 PAID로 보인다).
      기본 2분은 PG 취소 왕복(read-timeout 단위)보다 충분히 길고 운영자가 기다릴 만한 상한이다.
      0·음수는 진행 중인 선점을 즉시 가로채게 되므로 금지한다.
     */
    @Positive
    private long claimStaleAfterMs = 120_000L;
}
