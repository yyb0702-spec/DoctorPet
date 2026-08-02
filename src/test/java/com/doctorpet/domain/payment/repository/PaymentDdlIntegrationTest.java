package com.doctorpet.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
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
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Level 3 — payments 테이블 DDL·제약·조회 통합 검증(docs/testing/verification-guide.md).
 * Mockito 슬라이스가 확인하지 못하는 enum 문자열 매핑·UNIQUE 제약(reservation_id·merchant_payment_id)·
 * 스냅샷 컬럼 영속화를 실제 MySQL에 연결해 검증한다. H2 의존성이 없어 임베디드 대체 불가 → replace=NONE.
 * 로컬 application-local.yml 또는 CI services 컨테이너(MySQL 8)가 필요하다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// QuerydslConfig: @DataJpaTest 슬라이스가 병원·예약의 QueryDSL 커스텀 리포지토리를 스캔하며 JPAQueryFactory를
// 요구하므로 함께 import한다(기존 PaymentMethodDdlIntegrationTest와 동일 패턴).
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class PaymentDdlIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("PAID 확정 결제가 enum 문자열·채널·스냅샷·시각 컬럼까지 매핑대로 영속화된다")
    void paidPayment_persistsCorrectly() {
        Payment payment = Payment.pending(100L, "pay_1", 7L, "VISA", "1234", 50_000);
        payment.markPaid("PG-1", LocalDateTime.of(2026, 7, 28, 10, 0));
        Payment saved = paymentRepository.saveAndFlush(payment);
        entityManager.clear();

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getReservationId()).isEqualTo(100L);
        assertThat(reloaded.getMerchantPaymentId()).isEqualTo("pay_1");
        assertThat(reloaded.getPaymentMethodId()).isEqualTo(7L);
        assertThat(reloaded.getCardBrandSnapshot()).isEqualTo("VISA");
        assertThat(reloaded.getCardLast4Snapshot()).isEqualTo("1234");
        assertThat(reloaded.getAmount()).isEqualTo(50_000);
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reloaded.getPaymentChannel()).isEqualTo(PaymentChannel.BILLING_KEY);
        assertThat(reloaded.getPgPaymentId()).isEqualTo("PG-1");
        assertThat(reloaded.getPaidAt()).isNotNull();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("PENDING 선기록은 채널·paidAt이 비어 있고 status=PENDING으로 저장된다")
    void pendingPayment_persistsWithoutChannel() {
        Payment saved = paymentRepository.saveAndFlush(
                Payment.pending(101L, "pay_2", 7L, "KB", "5678", 30_000));
        entityManager.clear();

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.getPaymentChannel()).isNull();
        assertThat(reloaded.getPaidAt()).isNull();
        assertThat(reloaded.getOfflineRequiredAt()).isNull();
        assertThat(reloaded.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("OFFLINE_REQUIRED로 전환된 결제는 offline_required_at·failure_reason까지 매핑대로 영속화된다")
    void offlineRequiredPayment_persistsTimestamp() {
        Payment payment = Payment.pending(102L, "pay_3", 7L, "VISA", "1234", 40_000);
        payment.markOfflineRequired("NON_RETRIABLE", 0);
        Payment saved = paymentRepository.saveAndFlush(payment);
        entityManager.clear();

        Payment reloaded = paymentRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.OFFLINE_REQUIRED);
        assertThat(reloaded.getFailureReason()).isEqualTo("NON_RETRIABLE");
        assertThat(reloaded.getOfflineRequiredAt()).isNotNull();
        assertThat(reloaded.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("같은 reservation_id로 두 번 저장하면 UNIQUE 제약에 걸린다(예약당 1건·이중 청구 방지)")
    void duplicateReservationId_violatesUnique() {
        paymentRepository.saveAndFlush(Payment.pending(200L, "pay_a", 7L, "VISA", "1234", 10_000));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(
                Payment.pending(200L, "pay_b", 8L, "KB", "5678", 20_000)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 merchant_payment_id로 두 번 저장하면 UNIQUE 제약에 걸린다(외부 중복 승인 방지)")
    void duplicateMerchantPaymentId_violatesUnique() {
        paymentRepository.saveAndFlush(Payment.pending(201L, "pay_same", 7L, "VISA", "1234", 10_000));

        assertThatThrownBy(() -> paymentRepository.saveAndFlush(
                Payment.pending(202L, "pay_same", 8L, "KB", "5678", 20_000)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("existsByReservationId·findByReservationId·findByMerchantPaymentId가 동작한다")
    void lookups_work() {
        paymentRepository.saveAndFlush(Payment.pending(300L, "pay_lookup", 7L, "VISA", "1234", 10_000));
        entityManager.clear();

        assertThat(paymentRepository.existsByReservationId(300L)).isTrue();
        assertThat(paymentRepository.existsByReservationId(999L)).isFalse();
        assertThat(paymentRepository.findByReservationId(300L)).isPresent();
        assertThat(paymentRepository.findByMerchantPaymentId("pay_lookup")).isPresent();
    }
}
