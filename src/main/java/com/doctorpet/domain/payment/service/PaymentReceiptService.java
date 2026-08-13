package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentReceiptResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentRefund;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.entity.RefundStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.ReservationReceiptView;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentRefundRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  JSON 영수증 조회(고도화 결제 3.4). 보호자는 본인 결제만, 병원 스태프는 자병원 결제만 조회한다.
  예약 소유권·병원 정보는 예약 Repository를 직접 호출하지 않고 port로만 얻는다(가드레일, PaymentQueryService와 동일).

  검사 순서가 계약이다 — 권한을 먼저 보고 상태를 나중에 본다. 순서를 뒤집으면 남의 결제라도 "발급 가능한 상태인지"가
  409/200 응답 차이로 새어 나간다.

  발급 대상은 결제가 확정된 PAID·OFFLINE_PAID·REFUNDED뿐이다. PENDING은 승인 여부가 미확정이고
  OFFLINE_REQUIRED는 아직 수납 전이라 증빙이 성립하지 않는다(SA §5-2 결제 상태).
 */
@Service
@RequiredArgsConstructor
public class PaymentReceiptService {

    // 결제가 확정된 상태만 영수증을 낸다. REFUNDED도 "결제됐다가 되돌아간 이력"이므로 증빙 대상이다.
    private static final Set<PaymentStatus> RECEIPT_AVAILABLE_STATUSES =
            Set.of(PaymentStatus.PAID, PaymentStatus.OFFLINE_PAID, PaymentStatus.REFUNDED);

    private final PaymentRepository paymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final ReservationLookupPort reservationLookupPort;
    private final StaffHospitalPort staffHospitalPort;

    /** 보호자 본인 결제의 영수증. 본인 예약이 아니면 403. */
    @Transactional(readOnly = true)
    public PaymentReceiptResponse getForGuardian(Long paymentId, Long memberId) {
        Payment payment = loadPayment(paymentId);
        ReservationReceiptView reservation = loadReservation(payment.getReservationId());
        if (!reservation.guardianMemberId().equals(memberId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }
        return toReceipt(payment, reservation);
    }

    /** 병원 스태프 자병원 결제의 영수증. 소속 병원이 없거나 타병원 예약이면 403. */
    @Transactional(readOnly = true)
    public PaymentReceiptResponse getForHospital(Long paymentId, Long staffMemberId) {
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.FORBIDDEN));
        Payment payment = loadPayment(paymentId);
        ReservationReceiptView reservation = loadReservation(payment.getReservationId());
        if (!reservation.hospitalId().equals(staffHospitalId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }
        return toReceipt(payment, reservation);
    }

    private Payment loadPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }

    private ReservationReceiptView loadReservation(Long reservationId) {
        return reservationLookupPort.findForReceipt(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));
    }

    /**
     * 환불 상태는 결제가 REFUNDED로 확정된 경우에만 표시한다. PAID 결제에도 실패·진행 중 환불 이력이 남을 수
     * 있는데(SA §9-4 선점·재시도), 그것은 아직 결제 결과를 바꾸지 않은 병원 내부 처리 상태라 영수증에 드러내면
     * 환불되지 않은 결제가 환불된 것처럼 읽힌다. 사유·처리자·PG 취소 식별자는 감사용이라 어느 경우에도 담지 않는다.
     */
    private RefundStatus resolveRefundStatus(Payment payment) {
        if (payment.getStatus() != PaymentStatus.REFUNDED) {
            return null;
        }
        return paymentRefundRepository.findByPaymentId(payment.getId())
                .map(PaymentRefund::getStatus)
                .orElse(null);
    }

    private PaymentReceiptResponse toReceipt(Payment payment, ReservationReceiptView reservation) {
        if (!RECEIPT_AVAILABLE_STATUSES.contains(payment.getStatus())) {
            throw new ServiceException(PaymentErrorCode.RECEIPT_NOT_AVAILABLE);
        }
        // 항목화 이전에 청구된 결제는 행이 없어 빈 리스트가 된다. 임의 데이터를 만들어 채우지 않는다.
        return PaymentReceiptResponse.of(
                payment,
                reservation,
                paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId()),
                resolveRefundStatus(payment));
    }
}
