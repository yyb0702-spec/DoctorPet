package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  오프라인 정산의 트랜잭션 경계(#36, SA §9-4·§5). 자병원 검증 → 상태 판정 → 조건부 UPDATE 전이를 한 트랜잭션에서
  처리한다. 외부 발행(알림)은 여기 두지 않고 커밋 이후 오케스트레이터(PaymentOfflineSettlementService)가 한다.

  전이 규칙:
  - 이미 OFFLINE_PAID면 변경 없이 멱등 응답(반복 호출 안전).
  - OFFLINE_REQUIRED만 조건부 UPDATE로 OFFLINE_PAID 전이(동시 정산 시 갱신 1건만 성립).
  - PENDING·PAID 등 그 외 상태는 OFFLINE_PRECONDITION_FAILED(409).
 */
@Service
@RequiredArgsConstructor
public class PaymentOfflineSettleTxService {

    private final PaymentRepository paymentRepository;
    private final ReservationLookupPort reservationLookupPort;
    private final StaffHospitalPort staffHospitalPort;

    @Transactional
    public OfflineSettleOutcome settle(Long paymentId, Long staffMemberId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        ReservationChargeView reservation = assertOwnHospital(payment, staffMemberId);

        // 멱등: 이미 정산된 건은 변경 없이 반환한다(알림 재발행 없음).
        if (payment.getStatus() == PaymentStatus.OFFLINE_PAID) {
            return OfflineSettleOutcome.alreadySettled(
                    PaymentHistoryResponse.from(payment), reservation.guardianMemberId());
        }
        // 허용되지 않은 상태(PENDING·PAID)에서의 정산은 거부한다.
        if (payment.getStatus() != PaymentStatus.OFFLINE_REQUIRED) {
            throw new ServiceException(PaymentErrorCode.OFFLINE_PRECONDITION_FAILED);
        }

        int updated = paymentRepository.settleOfflineIfRequired(paymentId, LocalDateTime.now(), staffMemberId);
        if (updated == 0) {
            // load 이후 다른 요청이 먼저 정산했을 수 있다 → 재조회해 최종 상태로 판정(동시성).
            Payment latest = reload(paymentId);
            if (latest.getStatus() == PaymentStatus.OFFLINE_PAID) {
                return OfflineSettleOutcome.alreadySettled(
                        PaymentHistoryResponse.from(latest), reservation.guardianMemberId());
            }
            throw new ServiceException(PaymentErrorCode.OFFLINE_PRECONDITION_FAILED);
        }

        Payment settled = reload(paymentId);
        return OfflineSettleOutcome.freshlySettled(
                PaymentHistoryResponse.from(settled), reservation.guardianMemberId());
    }

    private ReservationChargeView assertOwnHospital(Payment payment, Long staffMemberId) {
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL));
        ReservationChargeView reservation = reservationLookupPort.findForCharge(payment.getReservationId())
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));
        if (!reservation.hospitalId().equals(staffHospitalId)) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }
        return reservation;
    }

    private Payment reload(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
    }
}
