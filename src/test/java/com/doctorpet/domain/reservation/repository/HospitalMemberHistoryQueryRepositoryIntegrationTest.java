package com.doctorpet.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.reservation.dto.response.HospitalMemberHistoryItemResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Level 3 — 병원 회원 이력 조회 통합 검증(실 MySQL). 자병원·해당 회원으로만 스코프(타 병원·타 회원 제외),
 * 활성 결제 LEFT JOIN(결제 없는 예약 포함), 슬롯 시작 시각 최근순을 실제 조인으로 확인한다. H2 미사용 → replace=NONE.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class, HospitalMemberHistoryQueryRepository.class})
class HospitalMemberHistoryQueryRepositoryIntegrationTest {

    private static final Long HOSPITAL_A = 9_001L;
    private static final Long HOSPITAL_B = 9_002L;
    private static final Long MEMBER = 9_100L;
    private static final Long OTHER_MEMBER = 9_101L;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private HospitalMemberHistoryQueryRepository repository;

    @Test
    @DisplayName("자병원·해당 회원 예약만 최근순으로 반환하고, 결제 없는 예약은 결제 필드가 null이다")
    void returnsScopedHistoryWithLeftJoinedPayment() {
        // 자병원·대상 회원: 결제 있는 최근 예약
        persist(HOSPITAL_A, MEMBER, "초코", LocalDateTime.of(2026, 8, 20, 9, 0), "pay_paid", true);
        // 자병원·대상 회원: 결제 없는 과거 예약
        persist(HOSPITAL_A, MEMBER, "초코", LocalDateTime.of(2026, 8, 10, 9, 0), null, false);
        // 타 병원·대상 회원 (제외)
        persist(HOSPITAL_B, MEMBER, "초코", LocalDateTime.of(2026, 8, 25, 9, 0), "pay_b", true);
        // 자병원·타 회원 (제외)
        persist(HOSPITAL_A, OTHER_MEMBER, "나비", LocalDateTime.of(2026, 8, 22, 9, 0), "pay_other", true);
        entityManager.flush();
        entityManager.clear();

        Page<HospitalMemberHistoryItemResponse> page =
                repository.findMemberHistory(HOSPITAL_A, MEMBER, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2L);
        assertThat(page.getContent()).extracting(HospitalMemberHistoryItemResponse::reservedAt)
                .containsExactly(
                        LocalDateTime.of(2026, 8, 20, 9, 0),
                        LocalDateTime.of(2026, 8, 10, 9, 0)
                );
        HospitalMemberHistoryItemResponse paid = page.getContent().get(0);
        assertThat(paid.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.amount()).isEqualTo(30_000);

        HospitalMemberHistoryItemResponse noPayment = page.getContent().get(1);
        assertThat(noPayment.paymentId()).isNull();
        assertThat(noPayment.paymentStatus()).isNull();
        assertThat(noPayment.amount()).isNull();
    }

    @Test
    @DisplayName("결제가 대체(superseded)된 예약은 결제 필드가 null이다 — LEFT JOIN이 활성 결제만 붙인다")
    void supersededPayment_yieldsNullPaymentFields() {
        // 예약은 남아 있지만 붙어 있던 결제가 대체돼 활성 결제가 없는 상태.
        // LEFT JOIN의 supersededAt IS NULL 조건이 대체된 결제를 걸러내야 예약 1행 + 결제 필드 null이 된다.
        persistWithSupersededPayment(HOSPITAL_A, MEMBER, "초코", LocalDateTime.of(2026, 8, 18, 9, 0));
        entityManager.flush();
        entityManager.clear();

        Page<HospitalMemberHistoryItemResponse> page =
                repository.findMemberHistory(HOSPITAL_A, MEMBER, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1L);
        HospitalMemberHistoryItemResponse row = page.getContent().get(0);
        assertThat(row.reservedAt()).isEqualTo(LocalDateTime.of(2026, 8, 18, 9, 0));
        assertThat(row.paymentId()).isNull();
        assertThat(row.paymentStatus()).isNull();
        assertThat(row.amount()).isNull();
    }

    @Test
    @DisplayName("활성 판정은 status가 아니라 superseded_at IS NULL이다 — OFFLINE_REQUIRED·REFUNDED 활성 결제도 결제 필드에 붙는다")
    void includesActiveNonPaidStatuses() {
        // 활성(superseded_at IS NULL) OFFLINE_REQUIRED — 자동결제 실패로 현장 수납 대상이지만 아직 대체되지 않음.
        persistWithPayment(HOSPITAL_A, MEMBER, "미수금", LocalDateTime.of(2026, 8, 14, 9, 0),
                p -> p.markOfflineRequired("NO_ACTIVE_METHOD", 3));
        // 활성(superseded_at IS NULL) REFUNDED — 전액 환불됐지만 정정 재청구로 대체되기 전이라 여전히 활성.
        persistWithPayment(HOSPITAL_A, MEMBER, "환불", LocalDateTime.of(2026, 8, 12, 9, 0),
                p -> {
                    p.markPaid("PG-REF", LocalDateTime.of(2026, 8, 12, 10, 0));
                    p.markRefunded(LocalDateTime.of(2026, 8, 12, 11, 0));
                });
        entityManager.flush();
        entityManager.clear();

        Page<HospitalMemberHistoryItemResponse> page =
                repository.findMemberHistory(HOSPITAL_A, MEMBER, PageRequest.of(0, 10));

        // status 필터가 실수로 들어가면(PAID만) 이 결제들이 null로 떨어지므로 회귀를 잡는다.
        assertThat(page.getTotalElements()).isEqualTo(2L);
        // 최근순: 8-14(OFFLINE_REQUIRED), 8-12(REFUNDED)
        HospitalMemberHistoryItemResponse offlineRequired = page.getContent().get(0);
        assertThat(offlineRequired.paymentStatus()).isEqualTo(PaymentStatus.OFFLINE_REQUIRED);
        assertThat(offlineRequired.paymentId()).isNotNull();
        assertThat(offlineRequired.amount()).isEqualTo(30_000);
        HospitalMemberHistoryItemResponse refunded = page.getContent().get(1);
        assertThat(refunded.paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.paymentId()).isNotNull();
    }

    private void persistWithPayment(
            Long hospitalId,
            Long memberId,
            String petName,
            LocalDateTime startAt,
            java.util.function.Consumer<Payment> mutate
    ) {
        ReservationSlot slot = ReservationSlot.create(hospitalId, startAt, startAt.plusMinutes(30));
        entityManager.persist(slot);
        entityManager.flush();

        Reservation reservation = Reservation.request(
                memberId, 7L, hospitalId, slot.getId(), 3L, petName, "DOG", startAt.minusDays(1)
        );
        entityManager.persist(reservation);
        entityManager.flush();

        Payment payment = Payment.pending(reservation.getId(), "pay_" + petName, 3L, "VISA", "1234", 30_000);
        mutate.accept(payment);
        entityManager.persist(payment);
    }

    private void persistWithSupersededPayment(
            Long hospitalId,
            Long memberId,
            String petName,
            LocalDateTime startAt
    ) {
        ReservationSlot slot = ReservationSlot.create(hospitalId, startAt, startAt.plusMinutes(30));
        entityManager.persist(slot);
        entityManager.flush();

        Reservation reservation = Reservation.request(
                memberId, 7L, hospitalId, slot.getId(), 3L, petName, "DOG", startAt.minusDays(1)
        );
        entityManager.persist(reservation);
        entityManager.flush();

        // supersede는 OFFLINE_REQUIRED·REFUNDED에서만 허용되므로 먼저 OFFLINE_REQUIRED로 전이한다.
        Payment payment = Payment.pending(reservation.getId(), "pay_superseded", 3L, "VISA", "1234", 30_000);
        payment.markOfflineRequired("SUPERSEDED_FIXTURE", 0);
        payment.supersede(startAt.plusHours(1));
        entityManager.persist(payment);
    }

    private void persist(
            Long hospitalId,
            Long memberId,
            String petName,
            LocalDateTime startAt,
            String merchantPaymentId,
            boolean paid
    ) {
        ReservationSlot slot = ReservationSlot.create(hospitalId, startAt, startAt.plusMinutes(30));
        entityManager.persist(slot);
        entityManager.flush();

        Reservation reservation = Reservation.request(
                memberId, 7L, hospitalId, slot.getId(), 3L, petName, "DOG", startAt.minusDays(1)
        );
        entityManager.persist(reservation);
        entityManager.flush();

        if (paid) {
            Payment payment = Payment.pending(reservation.getId(), merchantPaymentId, 3L, "VISA", "1234", 30_000);
            payment.markPaid("PG-" + merchantPaymentId, startAt.plusHours(1));
            entityManager.persist(payment);
        }
    }
}
