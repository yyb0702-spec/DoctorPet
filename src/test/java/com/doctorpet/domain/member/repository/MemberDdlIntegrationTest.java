package com.doctorpet.domain.member.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;

/**
 * Level 3 — members 테이블 DDL·제약 통합 검증(docs/testing/verification-guide.md).
 * Mockito/WebMvc 슬라이스 테스트는 UNIQUE 제약·Soft Delete 필터링처럼 실제 MySQL DDL이
 * 관여하는 동작을 확인하지 못한다(리뷰 지적 사항). 이 클래스는 실제 MySQL에 연결해 검증하므로
 * 로컬 application-local.yml 또는 CI services 컨테이너(MySQL 8)가 반드시 필요하다.
 * H2 등 임베디드 DB 의존성이 classpath에 없어 {@code @DataJpaTest}가 자동으로 대체할 수 없고,
 * 그 경우 원래 구성된 실제 DataSource를 그대로 쓰지만 의도를 명확히 하기 위해 replace=NONE을 명시한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
class MemberDdlIntegrationTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("email UNIQUE 제약: 같은 이메일로 두 번째 회원을 저장하면 DataIntegrityViolationException이 발생한다")
    void duplicateEmail_violatesUniqueConstraint() {
        memberRepository.saveAndFlush(Member.createGuardian("dup@example.com", "encoded", "닉네임1"));

        assertThatThrownBy(() ->
                memberRepository.saveAndFlush(Member.createGuardian("dup@example.com", "encoded", "닉네임2"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("신규 컬럼(failed_login_attempts·locked_until)이 매핑대로 저장·조회된다")
    void newColumns_persistCorrectly() {
        Member member = Member.createGuardian("columns@example.com", "encoded", "닉네임");
        member.recordLoginFailure(LocalDateTime.now());
        Member saved = memberRepository.saveAndFlush(member);
        entityManager.clear();

        Member reloaded = memberRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(reloaded.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("신규 컬럼(email_verified)이 기존 행에도 DEFAULT 0으로 안전하게 추가되고, verifyEmail() 이후 true로 저장·조회된다(백로그 P2)")
    void emailVerifiedColumn_defaultsFalseAndPersistsAfterVerification() {
        Member member = Member.createGuardian("emailverify@example.com", "encoded", "닉네임");
        Member saved = memberRepository.saveAndFlush(member);
        entityManager.clear();

        Member reloaded = memberRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isEmailVerified()).isFalse();

        reloaded.verifyEmail();
        memberRepository.saveAndFlush(reloaded);
        entityManager.clear();

        Member reloadedAfterVerify = memberRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloadedAfterVerify.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("Soft Delete(deleted_at) 후에는 findByEmail·existsByEmail 모두 조회되지 않는다")
    void softDeletedMember_isExcludedFromActiveQueries() {
        Member member = Member.createGuardian("softdelete@example.com", "encoded", "닉네임");
        Member saved = memberRepository.saveAndFlush(member);

        // 이 테스트는 Member.withdraw()의 부수효과(익명화 등)가 아니라 @SQLRestriction 기반
        // DB 레벨 필터링 자체만 검증하기 위해, 도메인 메서드를 거치지 않고 네이티브 쿼리로 직접
        // deleted_at만 채운다.
        entityManager.createNativeQuery("update members set deleted_at = now() where id = :id")
                .setParameter("id", saved.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(memberRepository.existsByEmail("softdelete@example.com")).isFalse();
        assertThat(memberRepository.findByEmail("softdelete@example.com")).isEmpty();
    }
}
