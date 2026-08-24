package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;

/**
 * 환불 선점(Tx1) 결과를 오케스트레이터로 넘기는 값 객체(#37).
 *
 * <p>{@code claimed=false}면 이미 환불이 완료된 결제라 PG를 호출하지 않고 멱등 응답으로 끝낸다.
 * {@code claimed=true}면 이 요청이 선점을 얻었으므로 트랜잭션 밖에서 PG 취소를 호출한 뒤 Tx2로 확정한다.
 * 선점에 실패한 요청(다른 요청이 진행 중)은 이 객체가 아니라 {@code REFUND_IN_PROGRESS} 예외로 끝난다.
 *
 * @param claimed          이 요청이 환불 선점을 얻었는지
 * @param refundId         환불 이력 행 id(선점한 경우에만 유효)
 * @param merchantRefundId PG 취소 멱등키. 재시도에도 이 값을 재사용한다
 * @param claimToken       선점 소유권 펜스. Tx2가 이 토큰으로 "아직 내가 선점을 쥐고 있는지"를 검사한다
 * @param merchantPaymentId 취소 대상 결제의 PG 멱등키
 * @param amount           환불 금액(원)
 * @param reservationId    관련 예약(알림 발행용)
 * @param guardianMemberId 알림 수신자(예약 보호자)
 * @param response         멱등 응답에 쓰는 현재 결제 상태(선점한 경우 Tx2 결과로 대체된다)
 */
public record RefundClaim(
        boolean claimed,
        Long refundId,
        String merchantRefundId,
        String claimToken,
        String merchantPaymentId,
        int amount,
        Long reservationId,
        Long guardianMemberId,
        PaymentHistoryResponse response
) {

    public static RefundClaim claimed(
            Long refundId, String merchantRefundId, String claimToken, String merchantPaymentId, int amount,
            Long reservationId, Long guardianMemberId, PaymentHistoryResponse response) {
        return new RefundClaim(true, refundId, merchantRefundId, claimToken, merchantPaymentId, amount,
                reservationId, guardianMemberId, response);
    }

    public static RefundClaim alreadyRefunded(
            Long reservationId, Long guardianMemberId, PaymentHistoryResponse response) {
        return new RefundClaim(false, null, null, null, null, 0, reservationId, guardianMemberId, response);
    }
}
