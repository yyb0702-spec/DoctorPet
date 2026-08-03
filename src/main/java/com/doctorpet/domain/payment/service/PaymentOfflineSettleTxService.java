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
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    // JpaAuditing의 createdAt/updatedAt과 같은 서울 기준 Clock(applicationClock). 조건부 UPDATE로 남기는
    // offlineSettledAt을 JVM 기본 시간대가 아니라 이 Clock으로 만들어 다른 결제 시각과 시간대가 어긋나지 않게 한다(PR #80 P2).
    private final Clock clock;

    // READ_COMMITTED로 둔다(PR #80 P2). 조건부 UPDATE가 0건이면 동시 정산에서 진 요청인데, MySQL 기본
    // REPEATABLE READ에서는 이 트랜잭션의 스냅샷이 첫 findById 시점(OFFLINE_REQUIRED)에 고정돼 이후 재조회가
    // 승자의 커밋(OFFLINE_PAID)을 보지 못해, 동일 결과를 요청한 멱등 호출인데도 409를 주게 된다. READ_COMMITTED는
    // 문장마다 최신 커밋을 읽으므로 재조회가 OFFLINE_PAID를 보고 200(alreadySettled)로 멱등 응답한다. 실제 전이는
    // 조건부 UPDATE(WHERE status=OFFLINE_REQUIRED)가 격리와 무관하게 원자적으로 1건만 성립시킨다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
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

        int updated = paymentRepository.settleOfflineIfRequired(
                paymentId, LocalDateTime.now(clock), staffMemberId);
        if (updated == 0) {
            // load 이후 다른 요청이 먼저 정산했다 → OFFLINE_REQUIRED의 유일한 전이는 OFFLINE_PAID(정산)뿐이므로
            // 동시 정산이 성립한 것이다. READ_COMMITTED라 재조회가 커밋된 OFFLINE_PAID를 보고 멱등 200으로 반환한다.
            Payment latest = reload(paymentId);
            if (latest.getStatus() == PaymentStatus.OFFLINE_PAID) {
                return OfflineSettleOutcome.alreadySettled(
                        PaymentHistoryResponse.from(latest), reservation.guardianMemberId());
            }
            // 여기 도달하면 상태 머신 밖의 예기치 못한 전이다 — 멱등으로 뭉치지 않고 그대로 드러낸다.
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
