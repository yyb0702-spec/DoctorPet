package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.config.PaymentRefundProperties;
import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentRefund;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.entity.RefundStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentRefundRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.review.service.ReviewRefundService;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/*
  환불의 트랜잭션 경계(#37, SA §9-4·§5). 청구(#34)와 같은 3단 구조를 따른다 — PG 취소(외부 호출)는 트랜잭션 밖에서
  해야 하므로 선점(Tx1)과 확정(Tx2)만 여기 두고, 오케스트레이션은 PaymentRefundService가 한다.

    Tx1 claim()     권한·상태 검증 + payment_refunds 선점(REQUESTED) [커밋]
    (트랜잭션 밖)     PG 취소 — merchantRefundId를 멱등키로
    Tx2 complete()  payment_refunds COMPLETED + payments PAID→REFUNDED + 리뷰·작성권 초기화 [커밋]
        fail()      payment_refunds FAILED (payments는 PAID 유지 → 같은 멱등키로 재시도 가능)

  선점 규칙(동시 환불이 PG 취소를 두 번 호출하지 않게 하는 핵심):
  - 이력 행 없음 → INSERT가 곧 선점. UNIQUE(payment_id) 위반은 동시 요청에서 진 것이므로 REFUND_IN_PROGRESS.
  - COMPLETED → 이미 환불 완료. PG 미호출 멱등 응답.
  - FAILED → 조건부 UPDATE로 재시도 선점(같은 merchantRefundId 재사용).
  - REQUESTED + claimed_at 신선 → 진행 중이므로 REFUND_IN_PROGRESS(가로채지 않는다).
  - REQUESTED + claimed_at 임계 초과 → 멈춘 선점을 회수해 재시도. 같은 멱등키라 PG가 기존 취소 결과를 주므로
    이중 취소가 아니고, "PG는 취소됐는데 payments만 PAID로 멈춘" 행이 이 경로로 복구된다.
 */
@Slf4j
@Service
public class PaymentRefundTxService {

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final ReservationLookupPort reservationLookupPort;
    private final StaffHospitalPort staffHospitalPort;
    private final MerchantRefundIdGenerator merchantRefundIdGenerator;
    // JpaAuditing의 createdAt/updatedAt과 같은 서울 기준 Clock(applicationClock). 조건부 UPDATE로 남기는
    // claimedAt·refundedAt을 JVM 기본 시간대가 아니라 이 Clock으로 만들어 다른 결제 시각과 어긋나지 않게 한다.
    private final Clock clock;
    private final PaymentRefundProperties properties;
    private final ReviewRefundService reviewRefundService;
    // 1차 캐시를 우회한 재조회(refresh)에만 쓴다 — 동시 환불에서 승자의 커밋을 확인하기 위해서다.
    private final EntityManager entityManager;

    public PaymentRefundTxService(
            PaymentRepository paymentRepository,
            PaymentRefundRepository paymentRefundRepository,
            ReservationLookupPort reservationLookupPort,
            StaffHospitalPort staffHospitalPort,
            MerchantRefundIdGenerator merchantRefundIdGenerator,
            Clock clock,
            PaymentRefundProperties properties,
            ReviewRefundService reviewRefundService,
            EntityManager entityManager
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentRefundRepository = paymentRefundRepository;
        this.reservationLookupPort = reservationLookupPort;
        this.staffHospitalPort = staffHospitalPort;
        this.merchantRefundIdGenerator = merchantRefundIdGenerator;
        this.clock = clock;
        this.properties = properties;
        this.reviewRefundService = reviewRefundService;
        this.entityManager = entityManager;
    }

    /**
     * Tx1 — 환불 전제 검증 + 선점. PG 취소 전에 커밋해, 앱이 취소 도중 죽어도 "요청했다"는 기록이 남게 한다.
     *
     * <p>READ_COMMITTED로 둔다(오프라인 정산과 같은 근거) — MySQL 기본 REPEATABLE READ에서는 트랜잭션 시작
     * 시점 스냅샷이 고정돼, 동시 환불의 승자가 커밋한 결과를 재조회가 보지 못해 멱등 판정이 어긋난다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RefundClaim claim(Long paymentId, Long staffMemberId, String reason) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
        ReservationChargeView reservation = assertOwnHospital(payment, staffMemberId);

        // 멱등: 이미 환불된 결제는 PG를 호출하지 않고 현재 상태를 반환한다(알림 재발행 없음).
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return RefundClaim.alreadyRefunded(payment.getReservationId(), reservation.guardianMemberId(),
                    PaymentHistoryResponse.from(payment));
        }
        // 환불 대상은 빌링키 자동 결제 완료뿐이다. PENDING(승인 불확실)·OFFLINE_REQUIRED·OFFLINE_PAID는 거부한다.
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new ServiceException(PaymentErrorCode.REFUND_PRECONDITION_FAILED);
        }
        // PAID면 채널은 항상 BILLING_KEY지만, 상태 머신 밖의 데이터로 PG 취소를 호출하지 않도록 방어적으로 확인한다.
        if (payment.getPaymentChannel() != PaymentChannel.BILLING_KEY) {
            throw new ServiceException(PaymentErrorCode.REFUND_PRECONDITION_FAILED);
        }

        PaymentRefund existing = paymentRefundRepository.findByPaymentId(paymentId).orElse(null);
        if (existing == null) {
            return insertClaim(payment, reservation, staffMemberId, reason);
        }
        return reclaim(existing, payment, reservation, staffMemberId, reason);
    }

    /**
     * Tx2 — PG 취소 성공 확정. 이력 행을 COMPLETED로, 결제를 PAID→REFUNDED로 전이하고 리뷰와 작성권을
     * 초기화한다. 모든 변경이 한 트랜잭션에 있어 일부만 반영된 상태가 남지 않는다.
     *
     * <p>{@code applied}는 이 호출이 실제로 결제 상태를 전이시켰는지다 — 멈춘 선점을 회수한 요청과 원래 요청이
     * 같은 멱등키로 각각 성공을 확정하는 경우, 조건부 UPDATE(WHERE status='PAID')가 1건만 성립시키므로
     * 알림이 중복 발행되지 않는다(청구 후확정의 applied와 같은 역할, PR #81 P1).
     *
     * <p>순서가 중요하다(PR #112 리뷰 P1): 이력 전이를 <b>먼저</b> 시도하고, 그것이 성립했을 때만 결제를 전이한다.
     * 이력 전이는 status와 소유권 펜스({@code claimToken})를 함께 검사하므로, 선점을 잃은 요청은 여기서 0건이
     * 되어 결제를 건드리지 못한다. 순서를 뒤집거나 펜스를 빼면 "이력 FAILED + 결제 REFUNDED"처럼 갈라진다.
     */
    @Transactional
    public RefundOutcome complete(Long refundId, Long paymentId, String claimToken, String pgCancelId) {
        LocalDateTime now = LocalDateTime.now(clock);
        int refundUpdated = paymentRefundRepository.markCompletedIfRequested(refundId, pgCancelId, now, claimToken);
        if (refundUpdated == 0) {
            /*
              선점을 잃었다(회수됐거나 이미 확정됨). 결제는 건드리지 않는다 — 이력을 못 바꾼 요청이 결제만
              REFUNDED로 바꾸면 "이력 FAILED/REQUESTED + 결제 REFUNDED"로 갈라진다(PR #112 리뷰 P1).
              PG 취소는 같은 멱등키라 현재 소유자가 재호출해도 기존 결과를 받아 정상 확정하므로 유실되지 않는다.
             */
            Payment current = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));
            log.warn("환불 확정 시점에 선점을 잃음 — 결제 상태를 바꾸지 않는다: paymentId={}, refundId={}, status={}",
                    paymentId, refundId, current.getStatus());
            return new RefundOutcome(PaymentHistoryResponse.from(current), false);
        }

        int paymentUpdated = paymentRepository.markRefundedIfPaid(paymentId, now);
        // markRefundedIfPaid는 @Modifying(clearAutomatically=true)라 영속성 컨텍스트가 비워진다 → 아래 조회는 실제 재조회다.
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        /*
          이력을 COMPLETED로 바꾼 요청은 결제도 반드시 전이시켜야 한다. 0건인데 결제가 REFUNDED도 아니면
          "이력 COMPLETED + 결제 미환불"로 어긋나는데, 이 상태는 스스로 복구되지 않는다 — 이후 재요청이
          reclaim()의 COMPLETED 분기에 걸려 계속 거부되기 때문이다(PR #112 리뷰 P1).

          그래서 로그만 남기고 커밋하지 않고 예외로 올려 이력 변경까지 롤백한다. 롤백하면 REQUESTED 선점이
          그대로 남으므로, 임계 경과 후 재시도가 같은 merchant_refund_id로 PG의 기존 취소 결과를 받아 정상
          확정한다 — 이미 이동한 돈의 기록이 유실되지 않고 자가 복구된다.

          결제가 이미 REFUNDED라면 결과적으로 원하는 상태이므로 롤백하지 않고 멱등 응답(applied=false)으로 둔다.
         */
        if (paymentUpdated == 0 && payment.getStatus() != PaymentStatus.REFUNDED) {
            log.error("PG 취소는 성립했으나 결제를 REFUNDED로 확정하지 못함 — 이력까지 롤백하고 재시도로 복구한다: "
                    + "paymentId={}, refundId={}, status={}", paymentId, refundId, payment.getStatus());
            throw new ServiceException(PaymentErrorCode.REFUND_STATE_CONFLICT);
        }
        if (paymentUpdated > 0) {
            reviewRefundService.deleteAndReset(payment.getReservationId());
        }
        return new RefundOutcome(PaymentHistoryResponse.from(payment), paymentUpdated > 0);
    }

    /**
     * Tx2 — PG 취소 실패 기록. 결제는 PAID로 남아 같은 멱등키로 재시도할 수 있다.
     * 선점을 이미 잃었다면(조건부 UPDATE 0건) 다른 요청의 결과를 덮어쓰지 않는다.
     */
    @Transactional
    public void fail(Long refundId, String claimToken, String failureReason) {
        // 펜스가 어긋나면(선점 회수됨) 갱신 0건이 되어 새 소유자의 선점을 FAILED로 덮지 않는다.
        paymentRefundRepository.markFailedIfRequested(
                refundId, failureReason, LocalDateTime.now(clock), claimToken);
    }

    private RefundClaim insertClaim(
            Payment payment, ReservationChargeView reservation, Long staffMemberId, String reason) {
        PaymentRefund refund = PaymentRefund.requested(
                payment.getId(), merchantRefundIdGenerator.generate(), payment.getAmount(),
                reason, staffMemberId, LocalDateTime.now(clock), newClaimToken());
        try {
            paymentRefundRepository.saveAndFlush(refund);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(payment_id) 경쟁에서 졌다 — 다른 요청이 방금 선점했으므로 진행 중으로 응답한다.
            // rollback-only 트랜잭션이라 재조회는 하지 않는다(청구 preRecord와 같은 이유).
            // merchant_refund_id는 UUID라 충돌이 사실상 불가능하므로 두 UNIQUE를 구분하지 않고 진행 중으로 본다 —
            // 재요청하면 앞선 선점의 결과에 따라 멱등 응답 또는 재시도로 정확히 갈린다.
            log.info("환불 선점 경쟁에서 진 요청 — 진행 중으로 응답: paymentId={}", payment.getId());
            throw new ServiceException(PaymentErrorCode.REFUND_IN_PROGRESS);
        }
        return RefundClaim.claimed(
                refund.getId(), refund.getMerchantRefundId(), refund.getClaimToken(),
                payment.getMerchantPaymentId(), refund.getAmount(), payment.getReservationId(),
                reservation.guardianMemberId(), PaymentHistoryResponse.from(payment));
    }

    private RefundClaim reclaim(
            PaymentRefund existing, Payment payment, ReservationChargeView reservation,
            Long staffMemberId, String reason) {
        if (existing.getStatus() == RefundStatus.COMPLETED) {
            /*
              이력은 COMPLETED인데 이 트랜잭션이 읽은 결제는 아직 PAID다. 동시 환불에서 흔히 밟는 경로다 —
              READ_COMMITTED는 문장마다 최신 커밋을 읽으므로, 승자가 Tx2(이력 COMPLETED + 결제 REFUNDED)를
              커밋하는 사이에 이 트랜잭션이 결제는 커밋 전에, 이력은 커밋 후에 읽을 수 있다. 두 전이가 한
              트랜잭션에 있어도 "읽는 쪽"까지 원자적이지는 않다.

              따라서 곧바로 오류로 단정하지 않고 결제를 다시 읽는다. REFUNDED면 승자가 확정을 마친 것이므로
              멱등 응답으로 돌려준다(PG 재호출·알림 재발행 없음). 재조회해도 PAID면 그때는 상태 머신 밖의
              실제 불일치이므로 드러낸다(운영 확인 대상).

              재조회는 반드시 refresh여야 한다 — findById는 이미 이 영속성 컨텍스트에 로드된 인스턴스를
              그대로 돌려주므로(1차 캐시) DB를 다시 보지 않아 낡은 PAID를 계속 읽는다. JPQL 조회도 마찬가지로
              관리 중인 엔티티는 캐시된 상태를 유지한다. 조건부 UPDATE 뒤 재조회가 최신을 보는 것은
              @Modifying(clearAutomatically=true)가 컨텍스트를 비우기 때문인데, 이 분기에는 선행 UPDATE가 없다.
             */
            entityManager.refresh(payment);
            if (payment.getStatus() == PaymentStatus.REFUNDED) {
                return RefundClaim.alreadyRefunded(payment.getReservationId(), reservation.guardianMemberId(),
                        PaymentHistoryResponse.from(payment));
            }
            log.error("환불 이력은 COMPLETED인데 재조회한 결제가 REFUNDED가 아님 — 수동 확인 필요: paymentId={}, refundId={}, status={}",
                    payment.getId(), existing.getId(), payment.getStatus());
            throw new ServiceException(PaymentErrorCode.REFUND_PRECONDITION_FAILED);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 선점을 넘겨받을 때마다 새 펜스 토큰을 발급한다 — 이전 소유자의 늦은 확정·실패가 이 선점을 덮지 못한다.
        String claimToken = newClaimToken();
        int claimed = existing.getStatus() == RefundStatus.FAILED
                ? paymentRefundRepository.claimFailedForRetry(
                        existing.getId(), reason, staffMemberId, now, claimToken)
                : paymentRefundRepository.claimStaleRequested(
                        existing.getId(), reason, staffMemberId,
                        now.minus(properties.getClaimStaleAfterMs(), ChronoUnit.MILLIS), now, claimToken);
        if (claimed == 0) {
            // FAILED 재시도 경쟁에서 졌거나, REQUESTED 선점이 아직 신선하다(진행 중) → 가로채지 않는다.
            throw new ServiceException(PaymentErrorCode.REFUND_IN_PROGRESS);
        }
        if (existing.getStatus() == RefundStatus.REQUESTED) {
            log.warn("멈춘 환불 선점을 회수해 재시도: paymentId={}, refundId={}, merchantRefundId={}",
                    payment.getId(), existing.getId(), existing.getMerchantRefundId());
        }
        return RefundClaim.claimed(
                existing.getId(), existing.getMerchantRefundId(), claimToken,
                payment.getMerchantPaymentId(), payment.getAmount(), payment.getReservationId(),
                reservation.guardianMemberId(), PaymentHistoryResponse.from(payment));
    }

    /** 선점 소유권 펜스 토큰. 추측 불가·정밀도 무관해야 하므로 UUID를 쓴다. */
    private String newClaimToken() {
        return java.util.UUID.randomUUID().toString();
    }

    /** 자병원 검증. 스태프 소속 병원을 인증 주체(memberId)로 재해석한다(요청 값 신뢰 금지, 보안). */
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
}
