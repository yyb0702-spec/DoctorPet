package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.entity.RefundStatus;
import com.doctorpet.domain.payment.port.ReservationReceiptView;
import java.time.LocalDateTime;
import java.util.List;

/*
  JSON 영수증(고도화 결제 3.4). PDF·전자문서·세금계산서·진료확인서는 범위 밖이고 이 구조화 응답만 제공한다.

  담는 것: 결제·예약 식별자, 결제 상태·채널, 결제 일시, 카드 스냅샷(brand·last4), 항목 목록·총액,
  병원·보호자·펫을 식별할 수 있는 기존 도메인 정보(예약 시점 펫 스냅샷 포함).

  담지 않는 것(내부값·민감정보):
  - 빌링키·카드번호 원본은 저장 자체가 없고, 표시는 brand·last4까지다(보안).
  - merchant_payment_id·pg_payment_id·failure_reason은 내부 식별자·처리 분류 코드라 노출하지 않는다
    (PaymentHistoryResponse와 같은 기준, PR #79 리뷰).
  - payment_refunds.reason·refunded_by·pg_cancel_id는 병원 내부 감사값이다. 특히 환불 사유는 보호자에게
    노출하지 않으므로(SA §4 payment_refunds) 보호자·병원 어느 응답에도 담지 않고 한 가지 응답 형태만 둔다.

  결제 일시는 상태에 따라 채워지는 컬럼이 다르므로 합치지 않고 그대로 내려준다 — 자동 결제는 paidAt,
  현장 수납은 offlineSettledAt이다. 환불된 결제는 refundStatus·refundedAt으로 환불 사실을 표시한다.
 */
public record PaymentReceiptResponse(
        Long paymentId,
        Long reservationId,
        Long hospitalId,
        Long guardianMemberId,
        Long petId,
        String petName,
        String petSpecies,
        PaymentStatus status,
        PaymentChannel paymentChannel,
        LocalDateTime paidAt,
        LocalDateTime offlineSettledAt,
        String cardBrandSnapshot,
        String cardLast4Snapshot,
        List<PaymentReceiptItemResponse> items,
        int totalAmount,
        RefundStatus refundStatus,
        LocalDateTime refundedAt
) {

    /**
     * 영수증을 조립한다. {@code items}는 항목화 도입 이전에 청구된 과거 결제라면 빈 리스트다 —
     * 임의 항목을 만들어 채우지 않고 빈 목록 그대로 내려준다(결제 전 예약에 빈 내역을 주는
     * PaymentQueryService와 같은 계약). {@code refundStatus}는 환불 이력이 없으면 null이다.
     */
    public static PaymentReceiptResponse of(
            Payment payment,
            ReservationReceiptView reservation,
            List<PaymentItem> items,
            RefundStatus refundStatus
    ) {
        return new PaymentReceiptResponse(
                payment.getId(),
                payment.getReservationId(),
                reservation.hospitalId(),
                reservation.guardianMemberId(),
                reservation.petId(),
                reservation.petName(),
                reservation.petSpecies(),
                payment.getStatus(),
                payment.getPaymentChannel(),
                payment.getPaidAt(),
                payment.getOfflineSettledAt(),
                payment.getCardBrandSnapshot(),
                payment.getCardLast4Snapshot(),
                items.stream().map(PaymentReceiptItemResponse::from).toList(),
                // 총액은 payments.amount가 정본이다. 항목 합계를 다시 계산해 내려주면 과거 결제(항목 없음)에서
                // 0원 영수증이 나오고, 항목이 있는 결제에서도 두 값이 갈릴 여지가 생긴다.
                payment.getAmount(),
                refundStatus,
                payment.getRefundedAt());
    }
}
