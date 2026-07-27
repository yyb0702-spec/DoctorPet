package com.doctorpet.global.gateway.payment;

import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentApproveResult;
import com.doctorpet.global.gateway.payment.dto.PaymentCancelCommand;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;

/**
 * 후불 결제 외부 연동 추상화. 애플리케이션 결제 로직과 PortOne SDK/API를 분리해
 * 테스트 가능성과 공급자 교체 가능성을 확보한다(SA §3·§9-4, 이슈 #38).
 *
 * <p>구현체는 공급자 응답을 도메인 중립 DTO로 변환하고, 실패는
 * {@link PaymentGatewayException}(재시도 성격 분류 포함)으로 던진다. 재시도 루프·결제 상태 전이·
 * 금액 검증 등은 이 계약의 책임이 아니라 상위 결제 서비스의 책임이다.
 *
 * <p>멱등: 승인·조회는 상위에서 생성한 {@code merchantPaymentId}를 그대로 전달·사용한다(외부 중복 승인 방지).
 */
public interface PaymentGateway {

    /**
     * 발급된 빌링키의 유효성을 검증하고 저장·표시에 안전한 카드 정보(brand·last4)를 반환한다.
     *
     * @throws PaymentGatewayException 공급자 호출 실패 시(재시도 성격 포함)
     */
    BillingKeyIssueResult verifyBillingKey(String billingKey);

    /**
     * 빌링키로 결제를 승인한다. {@code merchantPaymentId}를 멱등키로 전달한다.
     *
     * @throws PaymentGatewayException 승인 실패·미확정 시. 상위 서비스는 {@code failureReason}으로 분기한다.
     */
    PaymentApproveResult approve(PaymentApproveCommand command);

    /**
     * 멱등키로 결제 단건을 조회한다. 타임아웃·응답 유실 후 승인 여부 재확정에 쓴다(SA §9-4).
     *
     * @throws PaymentGatewayException 조회 실패 시
     */
    PaymentQueryResult query(String merchantPaymentId);

    /**
     * 결제 취소·환불(확장 지점). 환불은 MVP 제외이므로(SA §9-4) 기본 구현은 미지원 예외를 던진다.
     * 확장에서 결제 이력 테이블과 함께 실제 구현을 도입한다.
     */
    default void cancel(PaymentCancelCommand command) {
        throw new UnsupportedOperationException("결제 취소·환불은 MVP 범위 밖입니다(SA §9-4 확장 지점).");
    }
}
