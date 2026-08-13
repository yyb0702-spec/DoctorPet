package com.doctorpet.domain.payment.service;

import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.deleteItemsOf;
import static com.doctorpet.domain.payment.support.PaymentItemTestSupport.singleItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Level 3 — 선기록 UNIQUE 위반을 실제 MySQL로 제약별로 구분하는지 검증(STRICT, SA §9-4, PR #90 리뷰).
 * 기존 PaymentChargeServiceTest는 목으로 SQLException 메시지를 지어내 판별 로직만 확인하므로, 실제 MySQL이
 * 내려주는 ER_DUP_ENTRY 메시지 형식에 제약명이 담겨 substring 매칭이 실제로 동작하는지는 검증하지 못한다.
 * 여기서는 merchant_payment_id를 고정해 실제 uk_payments_merchant_payment_id 충돌을 일으키고, 그 위반이
 * DUPLICATE_CHARGE로 오분류되지 않고 원 예외로 전파되는지 확인한다. (reservation_id UNIQUE 경쟁 →
 * DUPLICATE_CHARGE의 실제 MySQL 검증은 동시 청구를 다루는 PaymentChargeConcurrencyTest가 담당한다.)
 * 게이트웨이 호출 전 단계(Tx1)만 다루므로 예약·스태프 port는 목으로 주입한다. 전체 컨텍스트(MySQL·Redis·env)가
 * 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class PaymentConstraintClassificationIntegrationTest {

    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long HOSPITAL_ID = 1L;
    private static final Long GUARDIAN_ID = 5L;
    private static final int AMOUNT = 50_000;
    // 두 예약이 같은 merchant_payment_id를 쓰도록 고정해 uk_payments_merchant_payment_id 충돌을 일으킨다.
    private static final String FIXED_MERCHANT_ID = "pay_fixed_collision";

    @Autowired private PaymentChargeService paymentChargeService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentItemRepository paymentItemRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private BillingKeyCryptor billingKeyCryptor;

    @MockitoBean private MerchantPaymentIdGenerator merchantPaymentIdGenerator;
    @MockitoBean private ReservationLookupPort reservationLookupPort;
    @MockitoBean private StaffHospitalPort staffHospitalPort;

    private Long firstReservationId;
    private Long secondReservationId;
    private Long paymentMethodId;

    @BeforeEach
    void setUp() {
        firstReservationId = System.nanoTime();
        secondReservationId = firstReservationId + 1;
        paymentMethodId = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(GUARDIAN_ID, billingKeyCryptor.encrypt("test-billing-key"), "VISA", "1234")).getId();

        given(staffHospitalPort.findHospitalIdByMemberId(STAFF_MEMBER_ID)).willReturn(Optional.of(HOSPITAL_ID));
        given(reservationLookupPort.findForChargeForUpdate(firstReservationId)).willReturn(Optional.of(
                new ReservationChargeView(firstReservationId, HOSPITAL_ID, GUARDIAN_ID, paymentMethodId, true)));
        given(reservationLookupPort.findForChargeForUpdate(secondReservationId)).willReturn(Optional.of(
                new ReservationChargeView(secondReservationId, HOSPITAL_ID, GUARDIAN_ID, paymentMethodId, true)));
        // 두 청구 모두 같은 merchant_payment_id를 발급받아 두 번째 선기록이 merchant UNIQUE 제약에 걸리게 한다.
        given(merchantPaymentIdGenerator.generate()).willReturn(FIXED_MERCHANT_ID);
    }

    @AfterEach
    void tearDown() {
        List.of(firstReservationId, secondReservationId).forEach(id ->
                paymentRepository.findByReservationId(id).ifPresent(payment -> {
                    deleteItemsOf(paymentItemRepository, payment.getId());
                    paymentRepository.delete(payment);
                }));
        paymentMethodRepository.deleteById(paymentMethodId);
    }

    @Test
    @DisplayName("merchant_payment_id UNIQUE 충돌은 DUPLICATE_CHARGE가 아니라 원 예외로 전파된다(실제 MySQL 제약 구분)")
    void merchantPaymentIdCollision_propagatesInsteadOfDuplicateCharge() {
        // 첫 청구는 정상 선기록(merchant_payment_id = FIXED_MERCHANT_ID로 커밋).
        paymentChargeService.preRecord(firstReservationId, STAFF_MEMBER_ID, singleItem(AMOUNT));

        // 두 번째는 예약이 달라 reservation_id UNIQUE는 통과하지만, 같은 merchant_payment_id라 그 UNIQUE에 걸린다.
        // 이 위반은 예약에 결제가 없으므로 DUPLICATE_CHARGE(ServiceException)가 아니라 원 무결성 예외로 전파돼야 한다.
        assertThatThrownBy(() -> paymentChargeService.preRecord(secondReservationId, STAFF_MEMBER_ID, singleItem(AMOUNT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ServiceException.class);

        // 첫 예약에는 결제가 남고, 두 번째 예약에는 결제가 생기지 않았다(오분류로 남았을 리 없음).
        assertThat(paymentRepository.findByReservationId(firstReservationId)).isPresent();
        assertThat(paymentRepository.findByReservationId(secondReservationId)).isEmpty();
    }

    @Test
    @DisplayName("사전 체크로 걸러지는 이중 청구는 그대로 DUPLICATE_CHARGE다(제약 구분이 정상 경로를 바꾸지 않는다)")
    void duplicateReservation_stillDuplicateCharge() {
        // 같은 예약으로 두 번 청구하면 두 번째는 existsByReservationId 사전 체크에서 DUPLICATE_CHARGE로 거부된다.
        paymentChargeService.preRecord(firstReservationId, STAFF_MEMBER_ID, singleItem(AMOUNT));

        assertThatThrownBy(() -> paymentChargeService.preRecord(firstReservationId, STAFF_MEMBER_ID, singleItem(AMOUNT)))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.DUPLICATE_CHARGE);
    }
}
