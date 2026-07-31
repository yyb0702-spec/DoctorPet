package com.doctorpet.domain.member.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Level 3 — 실제 MySQL로 email_verified 백필(리뷰 지적 P1 대응)을 검증한다. ddl-auto=update로
 * 컬럼이 추가되면서 기존 회원 행이 전부 false가 되는 상황을 재현하기 위해, 엔티티의
 * verifyEmail() 없이 저장한 회원(=인증 전 상태로 저장된, "이 컬럼이 생기기 전부터 있던 회원"의
 * 대역)을 대상으로 실행한다. MemberDdlIntegrationTest와 같은 이유로 @DataJpaTest +
 * replace=NONE을 쓴다 — H2 같은 임베디드 DB가 classpath에 없어 실제 구성된 DataSource를 그대로
 * 사용한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslConfig.class, EmailVerifiedBackfillRunner.class})
class EmailVerifiedBackfillRunnerIntegrationTest {

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SchemaMigrationRepository schemaMigrationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EmailVerifiedBackfillRunner runner;

    @Test
    void 마커가_없으면_인증_안된_기존_회원을_전부_인증완료로_백필하고_마커를_남긴다() throws Exception {
        Member existingMember =
                memberRepository.saveAndFlush(Member.createGuardian("backfill1@example.com", "encoded", "닉네임1"));
        entityManager.clear();
        assertThat(memberRepository.findById(existingMember.getId()).orElseThrow().isEmailVerified()).isFalse();

        runner.run(null);
        entityManager.clear();

        assertThat(memberRepository.findById(existingMember.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertThat(schemaMigrationRepository.existsById("email_verified_backfill_v1")).isTrue();
    }

    @Test
    void 마커가_있으면_이후_가입한_미인증_회원은_백필_대상에서_제외된다() throws Exception {
        schemaMigrationRepository.save(new SchemaMigrationRecord("email_verified_backfill_v1"));
        Member newMember =
                memberRepository.saveAndFlush(Member.createGuardian("backfill2@example.com", "encoded", "닉네임2"));
        entityManager.clear();

        runner.run(null);
        entityManager.clear();

        assertThat(memberRepository.findById(newMember.getId()).orElseThrow().isEmailVerified()).isFalse();
    }
}
