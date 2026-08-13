package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.dto.response.PaymentReceiptItemResponse;
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
 * Level 3 — 청구 항목화·영수증 통합 검증(고도화 결제 3.1·3.4, STRICT: 스키마·멱등 hot path).
 * 실제 MySQL에 청구를 태워 Payment.amount와 항목 합계 정합, 항목 스냅샷 영속화, 동시 청구에서의
 * "1건만 성립 + 고아 항목 없음"을 확인한다. 예약·스태프 port는 목으로 주입하고 게이트웨이는 fake다
 * (PaymentChargeConcurrencyTest와 동일한 인프라 전제 — 전체 컨텍스트가 없으면 BLOCKED).
 */
@SpringBootTest
class PaymentItemChargeIntegrationTest {

    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long OTHER_STAFF_MEMBER_ID = 19L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long GUARDIAN_ID = 5L;
    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired private PaymentApplicationService paymentApplicationService;
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
    private final List<Long> chargedReservationIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reservationId = System.nanoTime();
        chargedReservationIds.add(reservationId);
        paymentMethodId = paymentMethodRepository.saveAndFlush(PaymentMethod.issue(
                GUARDIAN_ID, billingKeyCryptor.encrypt("test-billing-key"), "VISA", "1234")).getId();

        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForChargeForUpdate(reservationId)).willReturn(Optional.of(
                new ReservationChargeView(reservationId, HOSPITAL_ID, GUARDIAN_ID, paymentMethodId, true)));
        given(reservationLookupPort.findForReceipt(reservationId)).willReturn(Optional.of(
                new ReservationReceiptView(reservationId, HOSPITAL_ID, GUARDIAN_ID, 11L, "나비", "CAT")));
    }

    @AfterEach
    void tearDown() {
        chargedReservationIds.forEach(id -> paymentRepository.findByReservationId(id).ifPresent(payment -> {
            jdbcTemplate.update("delete from payment_items where payment_id = ?", payment.getId());
            paymentRepository.delete(payment);
        }));
        chargedReservationIds.clear();
        paymentMethodRepository.deleteById(paymentMethodId);
    }

    @Test
    @DisplayName("항목 합계가 Payment.amount로 저장되고 항목 스냅샷이 함께 영속화된다")
    void charge_persistsItemsAndMatchingTotal() {
        PaymentChargeResponse response = paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, List.of(
                new PaymentItemCommand("진찰료", 1, 20_000),
                new PaymentItemCommand("주사", 2, 15_000)));

        Payment payment = paymentRepository.findById(response.paymentId()).orElseThrow();
        List<PaymentItem> items = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());

        assertThat(payment.getAmount()).isEqualTo(50_000);
        assertThat(items)
                .extracting(PaymentItem::getName, PaymentItem::getQuantity,
                        PaymentItem::getUnitPrice, PaymentItem::getAmount)
                .containsExactly(
                        tuple("진찰료", 1, 20_000, 20_000),
                        tuple("주사", 2, 15_000, 30_000));
        assertThat(items.stream().mapToInt(PaymentItem::getAmount).sum()).isEqualTo(payment.getAmount());
    }

    @Test
    @DisplayName("음수 할인 항목이 signed 컬럼에 그대로 저장되고 합계가 총액과 일치한다")
    void charge_withDiscountItem_persistsNegativeAmount() {
        PaymentChargeResponse response = paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, List.of(
                new PaymentItemCommand("진찰료", 1, 20_000),
                new PaymentItemCommand("재진 할인", 1, -5_000)));

        Payment payment = paymentRepository.findById(response.paymentId()).orElseThrow();
        List<PaymentItem> items = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());

        assertThat(payment.getAmount()).isEqualTo(15_000);
        assertThat(items).extracting(PaymentItem::getAmount).containsExactly(20_000, -5_000);
        assertThat(items.stream().mapToInt(PaymentItem::getAmount).sum()).isEqualTo(payment.getAmount());
    }

    @Test
    @DisplayName("항목 합계가 상한을 넘으면 결제도 항목도 저장되지 않는다(선기록 이전에 거부)")
    void charge_overMaxTotal_persistsNothing() {
        assertThatThrownBy(() -> paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID,
                List.of(new PaymentItemCommand("과다 항목", 2, 2_000_000))))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);

        assertThat(paymentRepository.findByReservationId(reservationId)).isEmpty();
    }

    @Test
    @DisplayName("PAID 결제의 영수증을 보호자·병원 스태프가 조회하고, 타 병원 스태프는 403으로 막힌다")
    void receipt_visibleToOwnerAndOwnHospitalOnly() {
        PaymentChargeResponse response = paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, List.of(
                new PaymentItemCommand("진찰료", 1, 20_000),
                new PaymentItemCommand("재진 할인", 1, -5_000)));
        assertThat(response.status()).isEqualTo(PaymentStatus.PAID);

        PaymentReceiptResponse guardianReceipt =
                paymentReceiptService.getForGuardian(response.paymentId(), GUARDIAN_ID);
        assertThat(guardianReceipt.totalAmount()).isEqualTo(15_000);
        assertThat(guardianReceipt.items()).extracting(PaymentReceiptItemResponse::amount)
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
        legacy.markPaid("PG-legacy", java.time.LocalDateTime.now());
        Long paymentId = paymentRepository.saveAndFlush(legacy).getId();

        PaymentReceiptResponse receipt = paymentReceiptService.getForGuardian(paymentId, GUARDIAN_ID);

        assertThat(receipt.items()).isEmpty();
        assertThat(receipt.totalAmount()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("동시 청구에서도 결제는 1건만 성립하고, 성립한 결제에만 항목이 붙어 고아 항목이 남지 않는다")
    void concurrentCharge_onlyOnePaymentWithItems() throws InterruptedException {
        // 공유 MySQL이라 다른 테스트가 남긴 행이 있을 수 있다. 절대값이 아니라 이 실행이 만든 증가분만 본다.
        int orphansBefore = orphanItemCount();
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
                    paymentApplicationService.charge(reservationId, STAFF_MEMBER_ID, List.of(
                            new PaymentItemCommand("진찰료", 1, 20_000),
                            new PaymentItemCommand("주사", 2, 15_000)));
                    success.incrementAndGet();
                } catch (ServiceException e) {
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
        List<PaymentItem> items = paymentItemRepository.findByPaymentIdOrderByIdAsc(payment.getId());
        assertThat(items).hasSize(2);
        assertThat(items.stream().mapToInt(PaymentItem::getAmount).sum()).isEqualTo(payment.getAmount());
        // 진 요청들이 만든 항목이 커밋됐다면 결제가 없는 항목 행이 늘어난다(선기록과 항목 저장이 같은 트랜잭션이
        // 아니면 정확히 이 증상이 나온다). 승자 외에는 아무 항목도 남기지 않아야 한다.
        assertThat(orphanItemCount()).isEqualTo(orphansBefore);
    }

    /** 결제가 존재하지 않는 payment_items 행 수. 고아 항목이 커밋됐는지 확인하는 유일한 관찰 지점이다. */
    private int orphanItemCount() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from payment_items i
                 where not exists (select 1 from payments p where p.id = i.payment_id)
                """, Integer.class);
        return count == null ? 0 : count;
    }
}
