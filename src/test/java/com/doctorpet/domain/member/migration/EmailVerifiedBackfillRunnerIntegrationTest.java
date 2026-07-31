package com.doctorpet.domain.member.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Level 3 — 실제 MySQL로 email_verified 백필(리뷰 지적 P1 대응)을 검증한다. ddl-auto=update로
 * 컬럼이 추가되면서 기존 회원 행이 전부 false가 되는 상황을 재현하기 위해, 엔티티의
 * verifyEmail() 없이 저장한 회원(=인증 전 상태로 저장된, "이 컬럼이 생기기 전부터 있던 회원"의
 * 대역)을 대상으로 실행한다. MemberDdlIntegrationTest와 같은 이유로 @DataJpaTest +
 * replace=NONE을 쓴다 — H2 같은 임베디드 DB가 classpath에 없어 실제 구성된 DataSource를 그대로
 * 사용한다.
 *
 * CI 기본값(member.email-verified-backfill.enabled)은 false다 — 이 러너는 실제
 * ApplicationRunner 빈이라, 켜둔 채로 두면 전체 컨텍스트를 로드하는 다른 모든 @SpringBootTest가
 * 부팅될 때마다 자동 실행되어 공유 MySQL 컨테이너에 마커를 영구히 남기고, 그러면 이 클래스가
 * "마커가 아직 없는" 상태를 전제로 하는 테스트를 실행할 수 없다. 이 클래스에서만
 * @TestPropertySource로 다시 켠다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslConfig.class, EmailVerifiedBackfillRunner.class})
@TestPropertySource(properties = "member.email-verified-backfill.enabled=true")
class EmailVerifiedBackfillRunnerIntegrationTest {

    private static final String MIGRATION_KEY = "email_verified_backfill_v1";

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private SchemaMigrationRepository schemaMigrationRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EmailVerifiedBackfillRunner runner;

    // 방어적 정리 — @DataJpaTest는 테스트 메서드마다 트랜잭션을 롤백하므로 원래는 불필요하지만,
    // 테스트 실행 순서(마커를 남기는 두 번째 테스트가 먼저 도는 경우 등)에 결과가 좌우되지
    // 않도록 각 테스트 시작 전에 마커가 없는 상태를 명시적으로 보장한다.
    @BeforeEach
    void cleanMarker() {
        // deleteById()는 대상이 없으면 EmptyResultDataAccessException을 던지므로, 있을 때만
        // 지운다 — 대부분의 실행에서는 애초에 마커가 없는 게 정상이라 매번 예외를 타면 안 된다.
        schemaMigrationRepository.findById(MIGRATION_KEY).ifPresent(schemaMigrationRepository::delete);
    }

    @Test
    void 마커가_없으면_인증_안된_기존_회원을_전부_인증완료로_백필하고_마커를_남긴다() throws Exception {
        Member existingMember =
                memberRepository.saveAndFlush(Member.createGuardian("backfill1@example.com", "encoded", "닉네임1"));
        entityManager.clear();
        assertThat(memberRepository.findById(existingMember.getId()).orElseThrow().isEmailVerified()).isFalse();

        runner.run(null);
        entityManager.clear();

        assertThat(memberRepository.findById(existingMember.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertThat(schemaMigrationRepository.existsById(MIGRATION_KEY)).isTrue();
    }

    @Test
    void 마커가_있으면_이후_가입한_미인증_회원은_백필_대상에서_제외된다() throws Exception {
        schemaMigrationRepository.save(new SchemaMigrationRecord(MIGRATION_KEY));
        Member newMember =
                memberRepository.saveAndFlush(Member.createGuardian("backfill2@example.com", "encoded", "닉네임2"));
        entityManager.clear();

        runner.run(null);
        entityManager.clear();

        assertThat(memberRepository.findById(newMember.getId()).orElseThrow().isEmailVerified()).isFalse();
    }
}
