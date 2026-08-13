package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.exception.PaymentMethodErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
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
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final ReservationLookupPort reservationLookupPort;
    private final StaffHospitalPort staffHospitalPort;
    private final MerchantPaymentIdGenerator merchantPaymentIdGenerator;
    // JpaAuditing의 updatedAt과 같은 서울 기준 Clock(applicationClock). 조건부 UPDATE로 갱신하는 updatedAt·
    // offlineRequiredAt을 JVM 기본 시간대가 아니라 이 Clock으로 만들어 정산 임계(§9-7)와 시간대가 어긋나지 않게 한다.
    private final Clock clock;
    private final int maxAmount;

    public PaymentChargeService(
            PaymentRepository paymentRepository,
            PaymentItemRepository paymentItemRepository,
            PaymentMethodRepository paymentMethodRepository,
            ReservationLookupPort reservationLookupPort,
            StaffHospitalPort staffHospitalPort,
            MerchantPaymentIdGenerator merchantPaymentIdGenerator,
            Clock clock,
            // 진료비 절대 상한(원). 코드 상수가 아니라 설정값으로 둬 배포 없이 상향 가능하게 한다(SA §9-4).
            @Value("${payment.charge.max-amount:3000000}") int maxAmount
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentItemRepository = paymentItemRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.reservationLookupPort = reservationLookupPort;
        this.staffHospitalPort = staffHospitalPort;
        this.merchantPaymentIdGenerator = merchantPaymentIdGenerator;
        this.clock = clock;
        this.maxAmount = maxAmount;
    }

    /**
     * 후확정 결과. {@code applied}는 이 호출이 실제로 상태를 전이시켰는지다 — 조건부 UPDATE가 0건이면(다른 경로가
     * 먼저 확정) false이고, 호출부는 상태를 덮어쓰거나 알림을 중복 발행하지 않는다(PR #81 P1). PENDING 유지도 전이가
     * 아니므로 applied=false다.
     */
    public record FinalizeResult(Payment payment, boolean applied) {
    }

    /**
     * Tx1 — 청구 전제 검증 + 멱등키 선기록(PENDING) + 항목 스냅샷 저장. 외부 승인 전에 커밋해,
     * UNIQUE(reservation_id)로 이중 청구를 차단하고 앱이 승인 도중 죽어도 레코드가 남게 한다
     * (check-then-act 금지, SA §9-4).
     *
     * <p>총액은 요청이 아니라 항목 합계로 서버가 계산한다(고도화 결제 3.1). 선기록과 항목 저장은 같은
     * 트랜잭션이라 "항목 없는 신규 결제"나 "결제 없는 고아 항목" 어느 쪽도 커밋되지 않는다 — 이중 청구 경쟁이
     * UNIQUE(reservation_id)에 걸려 롤백되면 그 요청이 만든 항목도 함께 사라진다.
     */
    @Transactional
    public PaymentPreRecord preRecord(Long reservationId, Long staffMemberId, List<PaymentItemCommand> items) {
        // 1) 자병원 권한: 스태프 소속 병원을 인증 주체(memberId)로 재해석한다(요청 값 신뢰 금지, 보안).
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL));

        // 2) 예약 로드(port 경유 — 예약 Repository 직접 호출 금지).
        ReservationChargeView reservation = reservationLookupPort.findForChargeForUpdate(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));

        // 3) 자병원·진료완료·금액 검증.
        if (!staffHospitalId.equals(reservation.hospitalId())) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }
        if (!reservation.treatmentCompleted()) {
            throw new ServiceException(PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);
        }
        // 총액은 요청이 아니라 항목 합계다. 곱셈·합산 overflow를 먼저 막은 뒤 기존 상한 검증을 그대로 적용한다.
        int amount = calculateAmount(items);

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
            // 사전 체크(existsByReservationId)를 통과한 동시 요청이 UNIQUE(reservation_id) 경쟁에 걸린 경우만
            // 도메인 에러(DUPLICATE_CHARGE)로 통일한다. UNIQUE 중복 위반이라는 것만으로는 부족하다 —
            // uk_payments_merchant_payment_id 위반이나 향후 payments에 추가될 다른 UNIQUE 위반까지
            // DUPLICATE_CHARGE로 뭉치면, 해당 예약에는 결제 레코드가 없는데도 호출자가 이미 청구됐다고
            // 오인한다(PR #90 리뷰, #83). 그래서 "reservation_id UNIQUE 위반인지"까지 좁혀 판별하고,
            // 그 외 무결성 위반(merchant_payment_id 충돌·NOT NULL·FK 등)은 DUPLICATE_CHARGE로 오분류하지 않고
            // 원 예외를 그대로 전파해 GlobalExceptionHandler가 실제 오류(500)로 드러내게 한다
            // (merchant_payment_id는 UUID라 충돌 자체가 사실상 불가능하므로 내부 오류로 처리한다).
            // rollback-only 트랜잭션이라 재조회는 하지 않는다.
            if (isReservationIdDuplicate(e)) {
                throw new ServiceException(PaymentErrorCode.DUPLICATE_CHARGE);
            }
            throw e;
        }

        // 7) 항목 스냅샷 저장. 결제 저장 이후라 payment_id가 확정돼 있고, 같은 트랜잭션이라 이중 청구 경쟁으로
        //    결제가 롤백되면 항목도 남지 않는다.
        paymentItemRepository.saveAll(items.stream()
                .map(item -> PaymentItem.snapshot(
                        payment.getId(), item.name().trim(), item.quantity(), item.unitPrice(),
                        lineAmount(item)))
                .toList());

        boolean methodActive = method.getStatus() == PaymentMethodStatus.ACTIVE;
        return new PaymentPreRecord(
                payment.getId(), merchantPaymentId, method.getBillingKeyEnc(),
                amount, methodActive, reservation.guardianMemberId(), reservationId);
    }

    /**
     * Tx2 — 외부 승인 결과로 결제 상태를 확정한다. 전이는 {@code WHERE status='PENDING'} 조건부 UPDATE로 원자화해
     * 후확정(#34)과 정산(#35)이 같은 PENDING 결제를 동시에 확정하는 경합을 막는다(SA §5·§9-7, PR #81 P1).
     * 조건부 UPDATE가 0건이면 다른 경로가 이미 확정한 것이므로 현재 상태를 그대로 반환하고 {@code applied=false}로
     * 알림 중복 발행을 막는다. PENDING 유지는 상태 전이가 아니므로 applied=false다.
     */
    @Transactional
    public FinalizeResult finalizeOutcome(Long paymentId, ChargeOutcome outcome) {
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = switch (outcome.type()) {
            case PAID -> paymentRepository.markPaidIfPending(
                    paymentId, outcome.pgPaymentId(), outcome.paidAt(), now);
            case OFFLINE_REQUIRED -> paymentRepository.markOfflineRequiredIfPending(
                    paymentId, outcome.failureReason(), outcome.retryCount(), now);
            case PENDING -> paymentRepository.remainPendingIfPending(
                    paymentId, outcome.retryCount(), outcome.failureReason(), now);
        };
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        boolean applied = updated > 0 && outcome.type() != ChargeOutcome.Type.PENDING;
        return new FinalizeResult(payment, applied);
    }

    /**
     * 항목 합계로 최종 청구 금액을 계산한다(고도화 결제 3.1). 요청이 보낸 총액은 쓰지 않는다.
     *
     * <p>계산 순서가 곧 안전장치다 — 곱셈·합산을 long으로 먼저 수행해 int overflow를 막고, 그 다음 int 범위와
     * 기존 절대 상한(설정값)을 검증한다. int로 먼저 곱하면 오버플로가 음수로 되감겨 상한 검증을 통과해버린다.
     * 항목 구조 문제(빈 목록·항목명 누락·수량 0 이하)는 INVALID_PAYMENT_ITEM, 금액 범위 문제는 INVALID_AMOUNT다.
     */
    private int calculateAmount(List<PaymentItemCommand> items) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException(PaymentErrorCode.INVALID_PAYMENT_ITEM);
        }
        long total = 0;
        for (PaymentItemCommand item : items) {
            total += lineAmount(item);
        }
        // 각 항목이 int 범위 안이어도 누적 합계는 넘칠 수 있다. long으로 더한 뒤 여기서 한 번에 판정한다.
        if (total <= 0 || total > maxAmount) {
            throw new ServiceException(PaymentErrorCode.INVALID_AMOUNT);
        }
        return (int) total;
    }

    /** 항목 금액(= 수량 × 단가). 곱셈을 long으로 해 int overflow를 막고, int 범위를 벗어나면 거부한다. */
    private int lineAmount(PaymentItemCommand item) {
        if (item == null || item.name() == null || item.name().isBlank()) {
            throw new ServiceException(PaymentErrorCode.INVALID_PAYMENT_ITEM);
        }
        if (item.quantity() <= 0) {
            throw new ServiceException(PaymentErrorCode.INVALID_PAYMENT_ITEM);
        }
        long lineAmount = (long) item.quantity() * item.unitPrice();
        if (lineAmount < Integer.MIN_VALUE || lineAmount > Integer.MAX_VALUE) {
            throw new ServiceException(PaymentErrorCode.INVALID_AMOUNT);
        }
        return (int) lineAmount;
    }

    // MySQL이 UNIQUE 제약 위반(중복 키, ER_DUP_ENTRY)에 내려주는 SQLState·벤더 오류코드. 이 둘로 "중복 키
    // 위반인지"를 판별한다(NOT NULL 1048·FK 1452 등 다른 무결성 위반은 이 코드가 아니다). SQLState/벤더
    // 코드는 제약 이름과 무관하게 항상 같다(GlobalExceptionHandler.resolveDataIntegrityErrorCode와 동일 근거).
    private static final String MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE = "23000";
    private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;
    // 예약당 결제 1건을 강제하는 UNIQUE 제약명(Payment 엔티티 @UniqueConstraint와 동일). 이중 청구 경쟁만 이
    // 제약을 위반하므로, 이 이름을 위반한 경우에만 DUPLICATE_CHARGE로 변환한다. 이 값은 우리 스키마가 정한
    // 이름이라 안정적이다 — 드라이버 의존적인 건 예외 "메시지 형식"이지 제약 이름 자체가 아니다.
    private static final String RESERVATION_ID_UNIQUE_CONSTRAINT = "uk_payments_reservation_id";

    // reservation_id UNIQUE(중복 청구) 위반만 참으로 본다. MySQL ER_DUP_ENTRY(23000/1062) 메시지에는 위반한
    // 제약명이 담긴다(예: "Duplicate entry '...' for key 'payments.uk_payments_reservation_id'"). 이 제약명을
    // 포함하지 않는 중복 키 위반(merchant_payment_id·향후 신규 UNIQUE)이나 비-중복 무결성 위반은 false다.
    private boolean isReservationIdDuplicate(DataIntegrityViolationException exception) {
        if (!(exception.getMostSpecificCause() instanceof SQLException sqlException)) {
            return false;
        }
        if (!MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())
                || sqlException.getErrorCode() != MYSQL_DUPLICATE_ENTRY_ERROR_CODE) {
            return false;
        }
        String message = sqlException.getMessage();
        return message != null
                && message.toLowerCase(Locale.ROOT).contains(RESERVATION_ID_UNIQUE_CONSTRAINT);
    }
}
