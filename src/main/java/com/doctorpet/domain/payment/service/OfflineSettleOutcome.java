package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;

/**
 * 오프라인 정산(#36) 트랜잭션 결과를 오케스트레이터로 넘기는 값 객체.
 * freshlySettled가 true인 경우(이 요청이 실제로 OFFLINE_PAID 전이를 수행)에만 커밋 이후 알림을 발행한다.
 * 이미 정산돼 있던 멱등 응답은 알림을 재발행하지 않는다.
 *
 * @param response         응답 DTO(정산 후/기정산 상태)
 * @param guardianMemberId 알림 수신자(예약 보호자)
 * @param freshlySettled   이 요청이 실제 정산을 수행했는지
 */
public record OfflineSettleOutcome(
        PaymentHistoryResponse response,
        Long guardianMemberId,
        boolean freshlySettled
) {

    public static OfflineSettleOutcome freshlySettled(PaymentHistoryResponse response, Long guardianMemberId) {
        return new OfflineSettleOutcome(response, guardianMemberId, true);
    }

    public static OfflineSettleOutcome alreadySettled(PaymentHistoryResponse response, Long guardianMemberId) {
        return new OfflineSettleOutcome(response, guardianMemberId, false);
    }
}
