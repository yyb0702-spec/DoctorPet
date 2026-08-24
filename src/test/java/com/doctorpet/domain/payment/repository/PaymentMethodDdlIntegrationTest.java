package com.doctorpet.domain.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Level 3 — payment_methods 테이블 DDL·제약·조회 통합 검증(docs/testing/verification-guide.md).
 * Mockito 슬라이스는 status enum 매핑·소유권 조회·소프트 삭제 필터링처럼 실제 MySQL DDL이
 * 관여하는 동작을 확인하지 못한다. 이 클래스는 실제 MySQL에 연결해 검증하므로
 * 로컬 application-local.yml 또는 CI services 컨테이너(MySQL 8)가 반드시 필요하다
 * (H2 의존성이 classpath에 없어 @DataJpaTest가 임베디드 DB로 대체할 수 없다 → replace=NONE).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        QuerydslConfig.class
})
class PaymentMethodDdlIntegrationTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("status enum이 문자열로 저장·조회되고 신규 컬럼이 매핑대로 영속화된다")
    void statusEnumAndColumns_persistCorrectly() {
        PaymentMethod saved = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(MEMBER_ID, "v1:encrypted", "SHINHAN", "1234"));
        entityManager.clear();

        PaymentMethod reloaded = paymentMethodRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(reloaded.getBillingKeyEnc()).isEqualTo("v1:encrypted");
        assertThat(reloaded.getCardBrand()).isEqualTo("SHINHAN");
        assertThat(reloaded.getCardLast4()).isEqualTo("1234");
        assertThat(reloaded.getStatus()).isEqualTo(PaymentMethodStatus.ACTIVE);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByIdAndMemberId는 소유자에게만 반환하고 타인에게는 빈 결과를 준다(소유권 조회)")
    void findByIdAndMemberId_enforcesOwnership() {
        PaymentMethod saved = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(MEMBER_ID, "v1:enc", "KB", "5678"));
        entityManager.clear();

        assertThat(paymentMethodRepository.findByIdAndMemberId(saved.getId(), MEMBER_ID)).isPresent();
        assertThat(paymentMethodRepository.findByIdAndMemberId(saved.getId(), OTHER_MEMBER_ID)).isEmpty();
    }

    @Test
    @DisplayName("ACTIVE 조회는 DELETED를 제외하지만, 소유권 조회는 상태와 무관하게 DELETED도 로드한다(#34 재확인용)")
    void activeQueryExcludesDeleted_butOwnershipLoadsIt() {
        PaymentMethod active = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(MEMBER_ID, "v1:active", "SHINHAN", "1111"));
        PaymentMethod deleted = PaymentMethod.issue(MEMBER_ID, "v1:deleted", "KB", "2222");
        deleted.markDeleted();
        PaymentMethod savedDeleted = paymentMethodRepository.saveAndFlush(deleted);
        entityManager.clear();

        List<PaymentMethod> activeList = paymentMethodRepository
                .findByMemberIdAndStatusOrderByCreatedAtDesc(MEMBER_ID, PaymentMethodStatus.ACTIVE);

        assertThat(activeList).extracting(PaymentMethod::getId).containsExactly(active.getId());

        Optional<PaymentMethod> loadedDeleted =
                paymentMethodRepository.findByIdAndMemberId(savedDeleted.getId(), MEMBER_ID);
        assertThat(loadedDeleted).isPresent();
        assertThat(loadedDeleted.get().getStatus()).isEqualTo(PaymentMethodStatus.DELETED);
    }

    @Test
    @DisplayName("소프트 삭제 후 ACTIVE 목록에서 제외된다")
    void softDelete_excludesFromActiveList() {
        PaymentMethod saved = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(MEMBER_ID, "v1:enc", "SHINHAN", "1234"));

        PaymentMethod loaded = paymentMethodRepository.findById(saved.getId()).orElseThrow();
        loaded.markDeleted();
        paymentMethodRepository.saveAndFlush(loaded);
        entityManager.clear();

        List<PaymentMethod> activeList = paymentMethodRepository
                .findByMemberIdAndStatusOrderByCreatedAtDesc(MEMBER_ID, PaymentMethodStatus.ACTIVE);

        assertThat(activeList).isEmpty();
    }
}
