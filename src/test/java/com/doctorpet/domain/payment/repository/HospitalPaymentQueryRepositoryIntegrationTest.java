package com.doctorpet.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.payment.dto.response.HospitalPaymentListItemResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Level 3 — 병원 결제 목록 조회 통합 검증(실 MySQL, docs/testing/verification-guide.md).
 * 자병원 스코프 격리·활성 결제만(대체 결제 제외)·예약/슬롯 조인 매핑을 Mockito가 못 보는
 * 실제 조인·생성 컬럼(active_reservation_id) 수준에서 확인한다. H2 미사용 → replace=NONE.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class, HospitalPaymentQueryRepository.class})
class HospitalPaymentQueryRepositoryIntegrationTest {

    private static final Long HOSPITAL_A = 8_001L;
    private static final Long HOSPITAL_B = 8_002L;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private HospitalPaymentQueryRepository repository;

    @Test
    @DisplayName("자병원의 활성 결제만 반환한다 — 타 병원·대체(superseded) 결제는 제외하고 예약/슬롯을 매핑한다")
    void returnsOnlyOwnHospitalActivePayments() {
        Payment aActive = persistReservationWithPayment(
                HOSPITAL_A, "초코", LocalDateTime.of(2026, 8, 20, 9, 0), "pay_a_active",
                p -> p.markPaid("PG-A", LocalDateTime.of(2026, 8, 20, 10, 0))
        );
        // 자병원이지만 대체된(활성 아님) 결제 — 결과에서 빠져야 한다.
        persistReservationWithPayment(
                HOSPITAL_A, "나비", LocalDateTime.of(2026, 8, 21, 9, 0), "pay_a_superseded",
                p -> p.supersede(LocalDateTime.of(2026, 8, 21, 10, 0))
        );
        // 타 병원 활성 결제 — 스코프 밖이라 빠져야 한다.
        persistReservationWithPayment(
                HOSPITAL_B, "몽이", LocalDateTime.of(2026, 8, 22, 9, 0), "pay_b_active",
                p -> p.markPaid("PG-B", LocalDateTime.of(2026, 8, 22, 10, 0))
        );
        entityManager.flush();
        entityManager.clear();

        Page<HospitalPaymentListItemResponse> page =
                repository.findActivePaymentsByHospitalId(HOSPITAL_A, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1L);
        HospitalPaymentListItemResponse row = page.getContent().get(0);
        assertThat(row.paymentId()).isEqualTo(aActive.getId());
        assertThat(row.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(row.petName()).isEqualTo("초코");
        // reservedAt은 예약 요청 시각이 아니라 슬롯 시작 시각이다.
        assertThat(row.reservedAt()).isEqualTo(LocalDateTime.of(2026, 8, 20, 9, 0));
        assertThat(row.amount()).isEqualTo(30_000);
        assertThat(row.paidAt()).isNotNull();
    }

    @Test
    @DisplayName("슬롯 시작 시각 최근순으로 정렬하고 페이지 경계를 지킨다")
    void ordersByReservedAtDescAndPaginates() {
        persistReservationWithPayment(HOSPITAL_A, "펫1", LocalDateTime.of(2026, 8, 10, 9, 0), "pay_1",
                p -> p.markPaid("PG-1", LocalDateTime.of(2026, 8, 10, 10, 0)));
        persistReservationWithPayment(HOSPITAL_A, "펫2", LocalDateTime.of(2026, 8, 12, 9, 0), "pay_2",
                p -> p.markPaid("PG-2", LocalDateTime.of(2026, 8, 12, 10, 0)));
        persistReservationWithPayment(HOSPITAL_A, "펫3", LocalDateTime.of(2026, 8, 11, 9, 0), "pay_3",
                p -> p.markPaid("PG-3", LocalDateTime.of(2026, 8, 11, 10, 0)));
        entityManager.flush();
        entityManager.clear();

        Page<HospitalPaymentListItemResponse> first =
                repository.findActivePaymentsByHospitalId(HOSPITAL_A, PageRequest.of(0, 2));

        assertThat(first.getTotalElements()).isEqualTo(3L);
        assertThat(first.getContent()).extracting(HospitalPaymentListItemResponse::petName)
                .containsExactly("펫2", "펫3"); // 8-12, 8-11 (최근순)
        assertThat(first.isFirst()).isTrue();
        assertThat(first.isLast()).isFalse();

        Page<HospitalPaymentListItemResponse> second =
                repository.findActivePaymentsByHospitalId(HOSPITAL_A, PageRequest.of(1, 2));
        assertThat(second.getContent()).extracting(HospitalPaymentListItemResponse::petName)
                .containsExactly("펫1"); // 8-10
        assertThat(second.isLast()).isTrue();
    }

    private Payment persistReservationWithPayment(
            Long hospitalId,
            String petName,
            LocalDateTime startAt,
            String merchantPaymentId,
            Consumer<Payment> mutate
    ) {
        ReservationSlot slot = ReservationSlot.create(hospitalId, startAt, startAt.plusMinutes(30));
        entityManager.persist(slot);
        entityManager.flush();

        Reservation reservation = Reservation.request(
                11L, 7L, hospitalId, slot.getId(), 3L, petName, "DOG", startAt.minusDays(1)
        );
        entityManager.persist(reservation);
        entityManager.flush();

        Payment payment = Payment.pending(reservation.getId(), merchantPaymentId, 3L, "VISA", "1234", 30_000);
        mutate.accept(payment);
        entityManager.persist(payment);
        return payment;
    }
}
