package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import com.doctorpet.domain.payment.entity.PaymentStatus;
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
    // 금액 규칙(항목 합계·overflow·절대 상한)은 초안 저장과 공유해야 하므로 정책 빈에 둔다(SA §9-4).
    private final PaymentAmountPolicy amountPolicy;

    public PaymentChargeService(
            PaymentRepository paymentRepository,
            PaymentItemRepository paymentItemRepository,
            PaymentMethodRepository paymentMethodRepository,
            ReservationLookupPort reservationLookupPort,
            StaffHospitalPort staffHospitalPort,
            MerchantPaymentIdGenerator merchantPaymentIdGenerator,
            Clock clock,
            PaymentAmountPolicy amountPolicy
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentItemRepository = paymentItemRepository;
        this.paymentMethodRepository = paymentMethodRepository;
        this.reservationLookupPort = reservationLookupPort;
        this.staffHospitalPort = staffHospitalPort;
        this.merchantPaymentIdGenerator = merchantPaymentIdGenerator;
        this.clock = clock;
        this.amountPolicy = amountPolicy;
    }

    /**
     * 후확정 결과. {@code applied}는 이 호출이 실제로 상태를 전이시켰는지다 — 조건부 UPDATE가 0건이면(다른 경로가
     * 먼저 확정) false이고, 호출부는 상태를 덮어쓰거나 알림을 중복 발행하지 않는다(PR #81 P1). PENDING 유지도 전이가
     * 아니므로 applied=false다.
     */
    public record FinalizeResult(Payment payment, boolean applied) {
    }

    /**
     * Tx1 — 청구 전제 검증 + 초안 항목 스탬프 + 멱등키 선기록(PENDING). 외부 승인 전에 커밋해,
     * UNIQUE(reservation_id)로 이중 청구를 차단하고 앱이 승인 도중 죽어도 레코드가 남게 한다
     * (check-then-act 금지, SA §9-4).
     *
     * <p>총액은 요청이 아니라 **예약 행 락을 잡은 뒤 재조회한 초안 항목의 합계**로 서버가 산출한다(SA §9-4).
     * 항목은 이미 초안으로 저장돼 있으므로(고도화 3.1) 여기서 새로 만들지 않고 {@code payment_id}를 스탬프해
     * 청구 시점 스냅샷을 고정한다. 스탬프와 선기록이 같은 트랜잭션이라, 이중 청구 경쟁이
     * UNIQUE(reservation_id)에 걸려 롤백되면 스탬프도 함께 되돌아가 초안 상태로 남는다.
     *
     * <p>초안 항목이 0건이면 청구를 거부한다({@code PAYMENT_ITEM_REQUIRED}) — 항목 없는 신규 결제를 만들지 않는다.
     */
    @Transactional
    public PaymentPreRecord preRecord(Long reservationId, Long staffMemberId, String draftToken) {
        // 1) 자병원 권한: 스태프 소속 병원을 인증 주체(memberId)로 재해석한다(요청 값 신뢰 금지, 보안).
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL));

        // 2) 예약 로드(port 경유 — 예약 Repository 직접 호출 금지). PESSIMISTIC_WRITE 락이라 이 아래에서는
        //    항목 초안 쓰기(PaymentItemDraftService)와 직렬화된다(SA §9-4 STRICT).
        ReservationChargeView reservation = reservationLookupPort.findForChargeForUpdate(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));

        // 3) 자병원·진료완료 검증.
        if (!staffHospitalId.equals(reservation.hospitalId())) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }
        if (!reservation.treatmentCompleted()) {
            throw new ServiceException(PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);
        }

        // 4) 이중 청구 사전 차단(정상 경로). 경쟁 상태는 아래 UNIQUE 위반으로 최종 방어한다.
        //    "결제 존재"가 아니라 "활성 결제 존재"로 본다 — 전액 환불된 REFUNDED 결제도 활성이라, 정상 청구는
        //    이 게이트에 걸려 거부되고 정정 재청구(preRecordCorrection)만 그 REFUNDED를 대체할 수 있다(고도화 3.5-a).
        //    대체된 과거 결제(superseded_at 설정)는 활성이 아니라 게이트를 통과시킨다.
        //    초안 조회보다 먼저 본다 — 청구가 끝나면 초안이 스탬프돼 0건이 되므로, 순서를 뒤집으면 재청구 시도가
        //    DUPLICATE_CHARGE 대신 PAYMENT_ITEM_REQUIRED로 답해 이중 청구 차단 계약(SA §9-4)이 흐려진다.
        if (paymentRepository.existsActiveByReservationId(reservationId)) {
            throw new ServiceException(PaymentErrorCode.DUPLICATE_CHARGE);
        }

        // 5) 락을 잡은 뒤 초안 항목을 재조회해 총액을 산출한다 — 락 이전에 읽으면 그 사이 초안이 바뀌어
        //    영수증 항목 합계와 payments.amount가 갈라진다. 0건이면 PAYMENT_ITEM_REQUIRED로 거부한다.
        List<PaymentItem> drafts =
                paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId);
        int amount = amountPolicy.totalOfItems(drafts);

        // 초안 저장(PUT)과 이 청구는 별도 요청이라 그 사이 잠금이 끊긴다. 그 틈에 다른 스태프가 초안을
        // 통째로 교체했으면 여기서 산출한 금액은 요청자가 화면에서 확인한 금액이 아니다 — 저장 응답이
        // 준 토큰과 대조해 다르면 청구하지 않는다(SA §9-4 "초안 교체 경합").
        if (!PaymentItemDraftToken.of(drafts).equals(draftToken)) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ITEM_CHANGED);
        }

        // 6) 예약에 확정된 결제수단 로드(소유권=예약 보호자). 상태와 무관하게 로드해 ACTIVE 여부는 여기서 판단한다.
        PaymentMethod method = paymentMethodRepository
                .findByIdAndMemberId(reservation.paymentMethodId(), reservation.guardianMemberId())
                .orElseThrow(() -> new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND));

        // 7) 카드 스냅샷 복사 + PENDING 선기록. UNIQUE(reservation_id)가 동시 이중 청구를 막는다.
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

        // 8) 초안 항목에 소속 결제를 스탬프해 청구 시점 스냅샷을 고정한다(SA §4 payment_items).
        //    조건부 UPDATE(WHERE payment_id IS NULL)라 이미 스탬프된 항목은 대상이 아니다. 갱신 건수가 4)에서
        //    센 초안 수와 다르면 락 밖 경로가 초안을 바꿨다는 뜻이므로, 총액과 어긋난 스냅샷을 커밋하지 않고
        //    예외로 롤백한다 — payments.amount == sum(items.amount) 불변식이 이 대조로 지켜진다.
        int stamped = paymentItemRepository.stampDraftsToPayment(reservationId, payment.getId());
        if (stamped != drafts.size()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ITEM_ALREADY_CHARGED);
        }

        boolean methodActive = method.getStatus() == PaymentMethodStatus.ACTIVE;
        return new PaymentPreRecord(
                payment.getId(), merchantPaymentId, method.getBillingKeyEnc(),
                amount, methodActive, reservation.guardianMemberId(), reservationId);
    }

    /**
     * Tx1(정정 재청구, 고도화 3.5-a) — 전액 환불된 REFUNDED 활성 결제를 대체하고 새 PENDING(correction_of)을 선기록한다.
     * 스태프가 환불 뒤 새 정정 초안을 작성한 상태에서 호출한다. 순서: 예약 행 락 → 활성 결제 REFUNDED 확인 →
     * 초안 재조회·총액 산출·토큰 대조 → 조건부 대체(supersedeIfRefunded) → 새 결제 선기록 → 초안 스탬프(SA §9-4).
     *
     * <p>대체가 1건 성립한 트랜잭션에서만 새 결제를 만든다 — 동시 정정 재청구는 supersedeIfRefunded에서 하나만
     * 1건을 받고 나머지는 0건이라 {@code PAYMENT_ALREADY_SUPERSEDED}(409)로 거부된다. 새 결제 INSERT는 활성 결제
     * UNIQUE가 최종 방어한다. 정상 청구와 달리 게이트(existsActiveByReservationId)는 통과하지 않는다 — REFUNDED가
     * 활성이라 정상 청구는 애초에 이 예약을 거부하고 이 경로만 REFUNDED를 대체할 수 있다.
     */
    @Transactional
    public PaymentPreRecord preRecordCorrection(Long reservationId, Long staffMemberId, String draftToken) {
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL));
        ReservationChargeView reservation = reservationLookupPort.findForChargeForUpdate(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));
        if (!staffHospitalId.equals(reservation.hospitalId())) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }
        if (!reservation.treatmentCompleted()) {
            throw new ServiceException(PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);
        }

        // 활성 결제가 REFUNDED일 때만 정정 재청구를 허용한다(고도화 3.5-a). 활성 없음·PENDING·PAID·OFFLINE_* 는 거부.
        Payment active = paymentRepository.findActiveByReservationId(reservationId)
                .filter(payment -> payment.getStatus() == PaymentStatus.REFUNDED)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.CORRECTION_PRECONDITION_FAILED));

        // 정정 초안 재조회·총액 산출·토큰 대조(정상 청구와 동일 계약, SA §9-4).
        List<PaymentItem> drafts =
                paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId);
        if (drafts.isEmpty()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ITEM_REQUIRED);
        }
        int amount = amountPolicy.totalOfItems(drafts);
        if (!PaymentItemDraftToken.of(drafts).equals(draftToken)) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ITEM_CHANGED);
        }

        PaymentMethod method = paymentMethodRepository
                .findByIdAndMemberId(reservation.paymentMethodId(), reservation.guardianMemberId())
                .orElseThrow(() -> new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND));

        // REFUNDED 활성 결제를 조건부로 대체(비활성화). 0건이면 그 사이 다른 정정이 먼저 대체한 것 → 409.
        int superseded = paymentRepository.supersedeIfRefunded(active.getId(), LocalDateTime.now(clock));
        if (superseded == 0) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ALREADY_SUPERSEDED);
        }

        // 새 정정 결제 선기록. 이전 멱등키를 재사용하면 공급자 멱등 캐시가 이전 승인을 돌려줘 정정 금액이 승인되지
        // 않으므로 merchant_payment_id를 새로 발급한다(SA §9-4).
        String merchantPaymentId = merchantPaymentIdGenerator.generate();
        Payment payment = Payment.pendingCorrection(
                reservationId, merchantPaymentId, method.getId(),
                method.getCardBrand(), method.getCardLast4(), amount, active.getId());
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // 대체가 성립했으면 슬롯이 비어 INSERT가 성공해야 한다. 그럼에도 활성 UNIQUE에 걸렸다면 다른 활성 결제가
            // 끼어든 경합이므로 대체 경합과 같은 의미(PAYMENT_ALREADY_SUPERSEDED)로 통일한다.
            if (isReservationIdDuplicate(e)) {
                throw new ServiceException(PaymentErrorCode.PAYMENT_ALREADY_SUPERSEDED);
            }
            throw e;
        }

        int stamped = paymentItemRepository.stampDraftsToPayment(reservationId, payment.getId());
        if (stamped != drafts.size()) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ITEM_ALREADY_CHARGED);
        }

        boolean methodActive = method.getStatus() == PaymentMethodStatus.ACTIVE;
        return new PaymentPreRecord(
                payment.getId(), merchantPaymentId, method.getBillingKeyEnc(),
                amount, methodActive, reservation.guardianMemberId(), reservationId);
    }

    /**
     * Tx1(셀프 복구, 고도화 3.3) — OFFLINE_REQUIRED 활성 결제를 대체하고 새 PENDING(recovery_of)을 선기록한다.
     * 보호자가 ACTIVE 결제수단을 지정해 명시적으로 재청구를 요청할 때 호출한다. 순서: 예약 행 락 → 보호자 본인 확인
     * → 활성 결제 OFFLINE_REQUIRED 확인 → 원 항목·총액 승계·정합 검증 → 조건부 대체(supersedeIfOfflineRequired)
     * → 새 결제 선기록 → 원 항목 복제(SA §9-4).
     *
     * <p>대체는 오프라인 정산(settleOfflineIfRequired)과 <b>같은 원 행·같은 superseded_at IS NULL 전제</b>를 걸어,
     * 셀프 복구와 현장 수납 중 먼저 커밋한 쪽만 성립하고 늦은 쪽은 0건 → {@code PAYMENT_ALREADY_SUPERSEDED}(409)다
     * (이중 수납 방지, STRICT). 원 총액은 승계만 하고 클라이언트는 금액·항목을 바꿀 수 없다. 항목화 이전(항목 없는)
     * 원 결제는 가짜 항목을 만들지 않고 총액만 승계한다(영수증 빈 배열 규칙).
     */
    @Transactional
    public PaymentPreRecord preRecordRecovery(Long reservationId, Long guardianMemberId, Long paymentMethodId) {
        ReservationChargeView reservation = reservationLookupPort.findForChargeForUpdate(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));
        // 보호자 본인 예약만. 요청 값이 아니라 인증 주체로 식별된 guardianMemberId와 예약 보호자를 대조한다(보안).
        if (!guardianMemberId.equals(reservation.guardianMemberId())) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }

        // 활성 결제가 OFFLINE_REQUIRED일 때만 셀프 복구를 허용한다(고도화 3.3). PENDING은 승인 불확정이라 이중 결제
        // 위험으로 금지하고 정산 스케줄러(#35)의 확정을 기다린다(SA §9-7).
        Payment active = paymentRepository.findActiveByReservationId(reservationId)
                .filter(payment -> payment.getStatus() == PaymentStatus.OFFLINE_REQUIRED)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.RECHARGE_PRECONDITION_FAILED));

        // 보호자가 새로 지정한 ACTIVE 결제수단. 재지정 API는 선기록이 있으면 거부하므로(3.2), 셀프 복구는 요청이
        // 결제수단을 직접 지정한다. 새 결제의 카드 스냅샷은 이 수단에서 복사한다.
        PaymentMethod method = paymentMethodRepository.findByIdAndMemberId(paymentMethodId, guardianMemberId)
                .orElseThrow(() -> new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND));
        if (method.getStatus() != PaymentMethodStatus.ACTIVE) {
            throw new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_ACTIVE);
        }

        // 원 총액 승계 + 원 항목 정합 검증. 항목이 있으면 복제 합계가 원 총액과 일치해야 하고, 다르면 대체 없이 롤백해
        // 운영자 확인 대상으로 남긴다(고도화 3.3). 항목화 이전 결제는 항목이 없어 검증 없이 총액만 승계한다.
        int amount = active.getAmount();
        List<PaymentItem> originalItems = paymentItemRepository.findByPaymentIdOrderByIdAsc(active.getId());
        if (!originalItems.isEmpty() && amountPolicy.totalOfItems(originalItems) != amount) {
            throw new ServiceException(PaymentErrorCode.RECOVERY_ITEM_MISMATCH);
        }

        // OFFLINE_REQUIRED 활성 결제를 조건부로 대체. 오프라인 정산·다른 셀프 복구가 먼저 처리했으면 0건 → 409.
        int superseded = paymentRepository.supersedeIfOfflineRequired(active.getId(), LocalDateTime.now(clock));
        if (superseded == 0) {
            throw new ServiceException(PaymentErrorCode.PAYMENT_ALREADY_SUPERSEDED);
        }

        String merchantPaymentId = merchantPaymentIdGenerator.generate();
        Payment payment = Payment.pendingRecovery(
                reservationId, merchantPaymentId, method.getId(),
                method.getCardBrand(), method.getCardLast4(), amount, active.getId());
        try {
            paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            if (isReservationIdDuplicate(e)) {
                throw new ServiceException(PaymentErrorCode.PAYMENT_ALREADY_SUPERSEDED);
            }
            throw e;
        }

        // 원 항목을 새 복구 결제로 복제한다(처음부터 스탬프된 상태). 항목화 이전 결제는 복제 없이 총액만 승계한다.
        if (!originalItems.isEmpty()) {
            paymentItemRepository.saveAll(originalItems.stream()
                    .map(item -> PaymentItem.copiedTo(payment.getId(), item))
                    .toList());
        }

        // 결제수단이 ACTIVE임을 위에서 강제했으므로 methodActive=true로 승인 파이프라인에 넘긴다.
        return new PaymentPreRecord(
                payment.getId(), merchantPaymentId, method.getBillingKeyEnc(),
                amount, true, guardianMemberId, reservationId);
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


    // MySQL이 UNIQUE 제약 위반(중복 키, ER_DUP_ENTRY)에 내려주는 SQLState·벤더 오류코드. 이 둘로 "중복 키
    // 위반인지"를 판별한다(NOT NULL 1048·FK 1452 등 다른 무결성 위반은 이 코드가 아니다). SQLState/벤더
    // 코드는 제약 이름과 무관하게 항상 같다(GlobalExceptionHandler.resolveDataIntegrityErrorCode와 동일 근거).
    private static final String MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE = "23000";
    private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;
    // 예약당 활성 결제 1건을 강제하는 UNIQUE 제약명(active_reservation_id 생성 컬럼 UNIQUE, 고도화 3.5-a로
    // uk_payments_reservation_id를 대체함). 이중 청구 경쟁만 이 제약을 위반하므로, 이 이름을 위반한 경우에만
    // DUPLICATE_CHARGE로 변환한다. 이 값은 우리 스키마가 정한 이름이라 안정적이다 — 드라이버 의존적인 건
    // 예외 "메시지 형식"이지 제약 이름 자체가 아니다.
    private static final String ACTIVE_RESERVATION_UNIQUE_CONSTRAINT = "uk_payments_active_reservation_id";

    // 활성 결제 UNIQUE(중복 청구) 위반만 참으로 본다. MySQL ER_DUP_ENTRY(23000/1062) 메시지에는 위반한
    // 제약명이 담긴다(예: "Duplicate entry '...' for key 'payments.uk_payments_active_reservation_id'"). 이 제약명을
    // 포함하지 않는 중복 키 위반(merchant_payment_id·다른 UNIQUE)이나 비-중복 무결성 위반은 false다.
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
                && message.toLowerCase(Locale.ROOT).contains(ACTIVE_RESERVATION_UNIQUE_CONSTRAINT);
    }
}
