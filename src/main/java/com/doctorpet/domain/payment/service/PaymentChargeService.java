package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.exception.PaymentMethodErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  진료비 청구의 트랜잭션 경계 담당(SA §9-4). 외부 호출(PortOne 승인)은 여기 넣지 않는다 — 선기록(Tx1)과
  후확정(Tx2)만 트랜잭션으로 처리하고, 그 사이 외부 승인은 상위 PaymentApplicationService가 트랜잭션 밖에서 한다.
  자기호출로는 트랜잭션 프록시가 적용되지 않으므로, 오케스트레이션과 트랜잭션 경계를 별도 빈으로 분리했다(가드레일).
 */
@Service
public class PaymentChargeService {

    private final PaymentRepository paymentRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final ReservationLookupPort reservationLookupPort;
    private final StaffHospitalPort staffHospitalPort;
    private final MerchantPaymentIdGenerator merchantPaymentIdGenerator;
    private final int maxAmount;

    public PaymentChargeService(
            PaymentRepository paymentRepository,
            PaymentMethodRepository paymentMethodRepository,
            ReservationLookupPort reservationLookupPort,
            StaffHospitalPort staffHospitalPort,
            MerchantPaymentIdGenerator merchantPaymentIdGenerator,
            // 진료비 절대 상한(원). 코드 상수가 아니라 설정값으로 둬 배포 없이 상향 가능하게 한다(SA §9-4).
            @Value("${payment.charge.max-amount:3000000}") int maxAmount
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.reservationLookupPort = reservationLookupPort;
        this.staffHospitalPort = staffHospitalPort;
        this.merchantPaymentIdGenerator = merchantPaymentIdGenerator;
        this.maxAmount = maxAmount;
    }

    /**
     * Tx1 — 청구 전제 검증 + 멱등키 선기록(PENDING). 외부 승인 전에 커밋해, UNIQUE(reservation_id)로 이중 청구를
     * 차단하고 앱이 승인 도중 죽어도 레코드가 남게 한다(check-then-act 금지, SA §9-4).
     */
    @Transactional
    public PaymentPreRecord preRecord(Long reservationId, Long staffMemberId, int amount) {
        // 1) 자병원 권한: 스태프 소속 병원을 인증 주체(memberId)로 재해석한다(요청 값 신뢰 금지, 보안).
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL));

        // 2) 예약 로드(port 경유 — 예약 Repository 직접 호출 금지).
        ReservationChargeView reservation = reservationLookupPort.findForCharge(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));

        // 3) 자병원·진료완료·금액 검증.
        if (!staffHospitalId.equals(reservation.hospitalId())) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }
        if (!reservation.treatmentCompleted()) {
            throw new ServiceException(PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);
        }
        validateAmount(amount);

        // 4) 이중 청구 사전 차단(정상 경로). 경쟁 상태는 아래 UNIQUE 위반으로 최종 방어한다.
        if (paymentRepository.existsByReservationId(reservationId)) {
            throw new ServiceException(PaymentErrorCode.DUPLICATE_CHARGE);
        }

        // 5) 예약에 확정된 결제수단 로드(소유권=예약 보호자). 상태와 무관하게 로드해 ACTIVE 여부는 여기서 판단한다.
        PaymentMethod method = paymentMethodRepository
                .findByIdAndMemberId(reservation.paymentMethodId(), reservation.guardianMemberId())
                .orElseThrow(() -> new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND));

        // 6) 카드 스냅샷 복사 + PENDING 선기록. UNIQUE(reservation_id)가 동시 이중 청구를 막는다.
        String merchantPaymentId = merchantPaymentIdGenerator.generate();
        Payment payment = Payment.pending(
                reservationId, merchantPaymentId, method.getId(),
                method.getCardBrand(), method.getCardLast4(), amount);
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // 사전 체크를 통과한 동시 요청이 UNIQUE(reservation_id)에 걸린 경우 → 도메인 에러로 통일.
            // 현재 payments의 무결성 위반 중 이 경로로 도달 가능한 것은 reservation_id UNIQUE 경쟁뿐이라
            // DUPLICATE_CHARGE로 뭉쳐도 안전하다: merchant_payment_id는 UUID라 충돌 사실상 불가,
            // NOT NULL 컬럼은 위에서 모두 채워 넣는다. 향후 다른 제약을 추가하면 제약별로 분기해야 오분류를 막는다.
            throw new ServiceException(PaymentErrorCode.DUPLICATE_CHARGE);
        }

        boolean methodActive = method.getStatus() == PaymentMethodStatus.ACTIVE;
        return new PaymentPreRecord(
                payment.getId(), merchantPaymentId, method.getBillingKeyEnc(),
                amount, methodActive, reservation.guardianMemberId(), reservationId);
    }

    /**
     * Tx2 — 외부 승인 결과로 결제 상태를 확정한다. 상태 전이는 엔티티 도메인 메서드로만 한다(SA §5 상태 머신).
     */
    @Transactional
    public Payment finalizeOutcome(Long paymentId, ChargeOutcome outcome) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        switch (outcome.type()) {
            case PAID -> payment.markPaid(outcome.pgPaymentId(), outcome.paidAt());
            case OFFLINE_REQUIRED -> payment.markOfflineRequired(outcome.failureReason(), outcome.retryCount());
            case PENDING -> payment.remainPending(outcome.retryCount(), outcome.failureReason());
        }
        return payment;
    }

    private void validateAmount(int amount) {
        if (amount <= 0 || amount > maxAmount) {
            throw new ServiceException(PaymentErrorCode.INVALID_AMOUNT);
        }
    }
}
