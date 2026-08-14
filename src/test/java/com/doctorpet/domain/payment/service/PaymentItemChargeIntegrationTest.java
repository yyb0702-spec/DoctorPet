package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.payment.dto.response.PaymentItemDraftResponse;
import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.support.PaymentItemTestSupport;
import com.doctorpet.domain.payment.dto.response.PaymentItemResponse;
import com.doctorpet.domain.payment.dto.response.PaymentReceiptResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.ReservationReceiptView;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Level 3 — 청구 항목 초안·스탬프·영수증 통합 검증(SA §4 payment_items·§9-4 청구 항목, STRICT: 스키마·멱등 hot path).
 * 실제 MySQL에 초안을 깔고 청구를 태워 스탬프·총액 정합, 청구 후 수정 차단, 동시 청구에서의 "1건만 성립 +
 * 고아 항목 없음", 항목 수정↔청구 선기록 직렬화를 확인한다. 예약·스태프 port는 목으로 주입하고 게이트웨이는
 * fake다(PaymentChargeConcurrencyTest와 동일한 인프라 전제 — 전체 컨텍스트가 없으면 BLOCKED).
 */
@SpringBootTest
class PaymentItemChargeIntegrationTest {

    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long OTHER_STAFF_MEMBER_ID = 19L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long GUARDIAN_ID = 5L;
    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired private PaymentApplicationService paymentApplicationService;
    @Autowired private PaymentItemDraftService paymentItemDraftService;
    @Autowired private PaymentReceiptService paymentReceiptService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentItemRepository paymentItemRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private BillingKeyCryptor billingKeyCryptor;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ReservationLookupPort reservationLookupPort;
    @MockitoBean private StaffHospitalPort staffHospitalPort;

    private Long reservationId;
    private Long paymentMethodId;
    private final List<Long> touchedReservationIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reservationId = System.nanoTime();
        touchedReservationIds.add(reservationId);
        paymentMethodId = paymentMethodRepository.saveAndFlush(PaymentMethod.issue(
                GUARDIAN_ID, billingKeyCryptor.encrypt("test-billing-key"), "VISA", "1234")).getId();

        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForChargeForUpdate(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, paymentMethodId, true)));
        given(reservationLookupPort.findForCharge(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, paymentMethodId, true)));
        given(reservationLookupPort.findForReceipt(reservationId)).willReturn(Optional.of(
                new ReservationReceiptView(reservationId, HOSPITAL_ID, GUARDIAN_ID, 11L, "나비", "CAT")));
    }

    @AfterEach
    void tearDown() {
        touchedReservationIds.forEach(id -> {
            jdbcTemplate.update("delete from payment_items where reservation_id = ?", id);
            paymentRepository.findByReservationId(id).ifPresent(paymentRepository::delete);
        });
        touchedReservationIds.clear();
        paymentMethodRepository.deleteById(paymentMethodId);
    }

    @Test
    @DisplayName("초안 항목을 청구하면 합계가 Payment.amount가 되고 그 항목들에 payment_id가 스탬프된다")
    void charge_stampsDraftsAndMatchesTotal() {
        saveDrafts(item("진찰료", 1, 20_000), item("주사", 2, 15_000));

        PaymentChargeResponse response = paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId));

        Payment payment = paymentRepository.findById(response.paymentId()).orElseThrow();
        List<PaymentItem> stamped = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());

        assertThat(payment.getAmount()).isEqualTo(50_000);
        assertThat(stamped)
                .extracting(PaymentItem::getName, PaymentItem::getQuantity,
                        PaymentItem::getUnitPrice, PaymentItem::getAmount)
                .containsExactly(
                        tuple("진찰료", 1, 20_000, 20_000),
                        tuple("주사", 2, 15_000, 30_000));
        assertThat(stamped.stream().mapToInt(PaymentItem::getAmount).sum()).isEqualTo(payment.getAmount());
        // 스탬프된 뒤에는 초안이 남지 않는다 — 같은 항목이 다음 청구에 재사용되지 않게 한다.
        assertThat(paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId)).isEmpty();
    }

    @Test
    @DisplayName("음수 할인 초안이 signed 컬럼에 그대로 저장되고 합계가 총액과 일치한다")
    void charge_withDiscountDraft_persistsNegativeAmount() {
        saveDrafts(item("진찰료", 1, 20_000), item("재진 할인", 1, -5_000));

        PaymentChargeResponse response = paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId));

        Payment payment = paymentRepository.findById(response.paymentId()).orElseThrow();
        List<PaymentItem> stamped = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());
        assertThat(payment.getAmount()).isEqualTo(15_000);
        assertThat(stamped).extracting(PaymentItem::getAmount).containsExactly(20_000, -5_000);
        assertThat(stamped.stream().mapToInt(PaymentItem::getAmount).sum()).isEqualTo(payment.getAmount());
    }

    @Test
    @DisplayName("초안 항목이 없으면 PAYMENT_ITEM_REQUIRED이고 결제가 생기지 않는다")
    void charge_withoutDrafts_rejected() {
        assertThatThrownBy(() -> paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId)))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_ITEM_REQUIRED);

        assertThat(paymentRepository.findByReservationId(reservationId)).isEmpty();
    }

    @Test
    @DisplayName("초안 전체 교체는 기존 초안을 지우고 새 목록으로 바꾼다")
    void replaceDrafts_replacesPreviousDrafts() {
        saveDrafts(item("진찰료", 1, 20_000));

        PaymentItemDraftResponse replacedResponse = paymentItemDraftService.replaceDrafts(
                reservationId, STAFF_MEMBER_ID, List.of(item("초음파", 1, 60_000), item("할인", 1, -10_000)));

        assertThat(replacedResponse.items()).extracting(PaymentItemResponse::name).containsExactly("초음파", "할인");
        assertThat(paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId))
                .extracting(PaymentItem::getName)
                .containsExactly("초음파", "할인");
    }

    @Test
    @DisplayName("다른 직원이 초안을 교체하면 먼저 저장한 직원의 청구는 PAYMENT_ITEM_CHANGED로 거부된다")
    void charge_rejectedWhenDraftReplacedByAnotherStaff() {
        // 직원 A가 항목을 저장하고 화면에서 합계 20,000원을 확인한 상태.
        PaymentItemDraftResponse savedByA =
                paymentItemDraftService.replaceDrafts(reservationId, STAFF_MEMBER_ID, List.of(item("진찰료", 1, 20_000)));

        // 청구 전에 직원 B가 같은 예약의 초안을 통째로 바꾼다(저장 API는 청구 전이라 정상 허용).
        paymentItemDraftService.replaceDrafts(reservationId, STAFF_MEMBER_ID, List.of(item("초음파", 1, 90_000)));

        // A가 자기 화면 기준 토큰으로 청구하면, 잠금 아래에서 다시 계산한 토큰과 달라 거부된다.
        assertThatThrownBy(() ->
                paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, savedByA.draftToken()))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_ITEM_CHANGED);

        // 결제가 생기지 않아야 한다 — A가 확인하지 않은 90,000원이 조용히 청구되면 안 된다.
        assertThat(paymentRepository.findByReservationId(reservationId)).isEmpty();
        assertThat(paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId))
                .extracting(PaymentItem::getName)
                .containsExactly("초음파");
    }

    @Test
    @DisplayName("청구 후 초안 수정을 시도하면 PAYMENT_ITEM_ALREADY_CHARGED이고 스탬프된 항목은 그대로 남는다")
    void replaceDrafts_afterCharge_rejectedAndStampedItemsUntouched() {
        saveDrafts(item("진찰료", 1, 20_000), item("주사", 2, 15_000));
        PaymentChargeResponse response = paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId));

        assertThatThrownBy(() -> paymentItemDraftService.replaceDrafts(
                reservationId, STAFF_MEMBER_ID, List.of(item("몰래 바꾼 항목", 1, 1_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.PAYMENT_ITEM_ALREADY_CHARGED);

        // 스탬프된 스냅샷이 손상되지 않았는지 실제 행으로 확인한다 — 이 금지가 정정 재청구 우회를 막는 경계다.
        Payment payment = paymentRepository.findById(response.paymentId()).orElseThrow();
        List<PaymentItem> stamped = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());
        assertThat(stamped).extracting(PaymentItem::getName).containsExactly("진찰료", "주사");
        assertThat(stamped.stream().mapToInt(PaymentItem::getAmount).sum()).isEqualTo(payment.getAmount());
    }

    @Test
    @DisplayName("PAID 결제의 영수증을 보호자·자병원 스태프가 조회하고, 타 병원 스태프·타인은 403으로 막힌다")
    void receipt_visibleToOwnerAndOwnHospitalOnly() {
        saveDrafts(item("진찰료", 1, 20_000), item("재진 할인", 1, -5_000));
        PaymentChargeResponse response = paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId));
        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);

        PaymentReceiptResponse guardianReceipt =
                paymentReceiptService.getForGuardian(response.paymentId(), GUARDIAN_ID);
        assertThat(guardianReceipt.totalAmount()).isEqualTo(15_000);
        assertThat(guardianReceipt.items()).extracting(PaymentItemResponse::amount)
                .containsExactly(20_000, -5_000);
        assertThat(guardianReceipt.petName()).isEqualTo("나비");
        assertThat(guardianReceipt.cardLast4Snapshot()).isEqualTo("1234");

        PaymentReceiptResponse hospitalReceipt =
                paymentReceiptService.getForHospital(response.paymentId(), STAFF_MEMBER_ID);
        assertThat(hospitalReceipt.paymentId()).isEqualTo(guardianReceipt.paymentId());

        given(staffHospitalPort.findHospitalIdByMemberId(OTHER_STAFF_MEMBER_ID))
                .willReturn(Optional.of(HOSPITAL_ID + 1));
        assertThatThrownBy(() -> paymentReceiptService.getForHospital(response.paymentId(), OTHER_STAFF_MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> paymentReceiptService.getForGuardian(response.paymentId(), GUARDIAN_ID + 1))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("항목화 이전에 청구된 결제(항목 행 없음)도 빈 항목 목록으로 영수증이 조회된다")
    void receipt_legacyPaymentWithoutItems_returnsEmptyItems() {
        // 항목 도입 전 데이터를 재현하려고 항목 없이 결제만 저장한다(백필하지 않는 것이 계약이다).
        Payment legacy = Payment.pending(reservationId, "pay_legacy_" + reservationId, paymentMethodId,
                "VISA", "1234", 50_000);
        legacy.markPaid("PG-legacy", LocalDateTime.now());
        Long paymentId = paymentRepository.saveAndFlush(legacy).getId();

        PaymentReceiptResponse receipt = paymentReceiptService.getForGuardian(paymentId, GUARDIAN_ID);

        assertThat(receipt.items()).isEmpty();
        assertThat(receipt.totalAmount()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("동시 청구에서도 결제는 1건만 성립하고, 초안은 그 결제에만 스탬프돼 고아 항목이 남지 않는다")
    void concurrentCharge_onlyOnePaymentWithStampedItems() throws InterruptedException {
        int orphansBefore = orphanItemCount();
        saveDrafts(item("진찰료", 1, 20_000), item("주사", 2, 15_000));

        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger unexpectedFailure = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId));
                    success.incrementAndGet();
                } catch (ServiceException e) {
                    // 승자 외에는 이중 청구(DUPLICATE_CHARGE)로 막힌다. 초안이 이미 스탬프돼 사라진 뒤에 도착한
                    // 요청도 DUPLICATE_CHARGE여야 한다 — 이중청구 검사가 초안 조회보다 앞이라 그렇다.
                    if (e.getErrorCode() == PaymentErrorCode.DUPLICATE_CHARGE) {
                        duplicate.incrementAndGet();
                    } else {
                        unexpectedFailure.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedFailure.get()).isZero();
        assertThat(success.get()).isEqualTo(1);
        assertThat(duplicate.get()).isEqualTo(CONCURRENT_REQUESTS - 1);

        Payment payment = paymentRepository.findByReservationId(reservationId).orElseThrow();
        List<PaymentItem> stamped = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());
        assertThat(stamped).hasSize(2);
        assertThat(stamped.stream().mapToInt(PaymentItem::getAmount).sum()).isEqualTo(payment.getAmount());
        // 결제가 없는 항목 행이 늘지 않았는지 본다(선기록과 스탬프가 같은 트랜잭션이 아니면 이 증상이 나온다).
        assertThat(orphanItemCount()).isEqualTo(orphansBefore);
    }

    @Test
    @DisplayName("항목 수정과 청구가 동시에 와도 예약 행 락으로 직렬화돼, 총액과 스탬프된 항목 합계가 갈라지지 않는다")
    void concurrentDraftEditAndCharge_keepsTotalConsistent() throws InterruptedException {
        saveDrafts(item("진찰료", 1, 20_000), item("주사", 2, 15_000));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Throwable> unexpected = new ArrayList<>();

        // 한쪽은 청구, 다른 쪽은 항목 교체를 동시에 시도한다. 두 경로는 같은 예약 행을 PESSIMISTIC_WRITE로
        // 잠그므로 하나가 끝난 뒤 다른 하나가 실행된다 — 청구가 먼저면 교체가 409, 교체가 먼저면 청구가
        // 교체된 항목 합계로 성립한다. 어느 순서에서도 총액과 스탬프된 항목 합계는 같아야 한다.
        executor.submit(() -> runGuarded(readyLatch, startLatch, doneLatch, unexpected,
                () -> paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, PaymentItemTestSupport.draftToken(paymentItemRepository, reservationId))));
        executor.submit(() -> runGuarded(readyLatch, startLatch, doneLatch, unexpected,
                () -> paymentItemDraftService.replaceDrafts(
                        reservationId, STAFF_MEMBER_ID, List.of(item("초음파", 1, 70_000)))));

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpected).isEmpty();

        Optional<Payment> charged = paymentRepository.findByReservationId(reservationId);
        if (charged.isPresent()) {
            Payment payment = charged.get();
            List<PaymentItem> stamped = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());
            assertThat(stamped).isNotEmpty();
            assertThat(stamped.stream().mapToInt(PaymentItem::getAmount).sum())
                    .isEqualTo(payment.getAmount());
            // 청구가 이겼다면 교체는 반영되지 않았고, 교체가 이겼다면 교체된 항목으로 청구됐다. 섞이면 안 된다.
            assertThat(payment.getAmount()).isIn(50_000, 70_000);
        } else {
            // 청구가 예외로 끝난 경우(교체가 이겼고 청구는 다음 시도로 밀린 경우)에는 초안만 남아 있어야 한다.
            assertThat(paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId))
                    .isNotEmpty();
        }
    }

    private void runGuarded(
            CountDownLatch readyLatch, CountDownLatch startLatch, CountDownLatch doneLatch,
            List<Throwable> unexpected, Runnable action
    ) {
        readyLatch.countDown();
        try {
            startLatch.await();
            action.run();
        } catch (ServiceException e) {
            // 직렬화 결과로 지는 쪽은 도메인 에러를 받는다(청구 후 교체=409, 교체 후 청구는 성립). 그 밖은 실패다.
            if (e.getErrorCode() != PaymentErrorCode.PAYMENT_ITEM_ALREADY_CHARGED
                    && e.getErrorCode() != PaymentErrorCode.DUPLICATE_CHARGE
                    && e.getErrorCode() != PaymentErrorCode.PAYMENT_ITEM_REQUIRED) {
                synchronized (unexpected) {
                    unexpected.add(e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            synchronized (unexpected) {
                unexpected.add(e);
            }
        } finally {
            doneLatch.countDown();
        }
    }

    private PaymentItemCommand item(String name, int quantity, int unitPrice) {
        return new PaymentItemCommand(name, quantity, unitPrice);
    }

    private void saveDrafts(PaymentItemCommand... items) {
        paymentItemDraftService.replaceDrafts(reservationId, STAFF_MEMBER_ID, List.of(items));
    }

    /** 결제가 존재하지 않는 payment_items 행 수. 고아 항목이 커밋됐는지 확인하는 관찰 지점이다. */
    private int orphanItemCount() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from payment_items i
                 where i.payment_id is not null
                   and not exists (select 1 from payments p where p.id = i.payment_id)
                """, Integer.class);
        return count == null ? 0 : count;
    }
}
