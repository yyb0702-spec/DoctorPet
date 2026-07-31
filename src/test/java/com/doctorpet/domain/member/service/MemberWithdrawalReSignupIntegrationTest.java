package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * Level 3 — SA 부록B 필수 시나리오: 탈퇴 후 동일 이메일 재가입(리뷰 지적 P1).
 * {@code MemberDdlIntegrationTest.softDeletedMember_isExcludedFromActiveQueries()}는 네이티브
 * 쿼리로 {@code deleted_at}만 직접 바꿔 @SQLRestriction 필터링만 검증했고, 실제
 * {@code MemberService.withdraw()}가 이메일을 익명화(withdrawn_{id}@deleted.doctorpet)하는
 * 경로까지는 확인하지 못했다 — 그래서 탈퇴 후 같은 이메일로 재가입할 때 실제로 email UNIQUE
 * 제약과 충돌하지 않는지는 검증된 적이 없었다. 이 테스트는 실제 서비스 메서드를 호출해 그
 * 간극을 메운다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuerydslConfig.class, MemberService.class})
class MemberWithdrawalReSignupIntegrationTest {

    private static final String EMAIL = "resignup@example.com";

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberService memberService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 탈퇴한_회원의_이메일로_다시_가입해도_UNIQUE_충돌이_없다() {
        Member original =
                memberRepository.saveAndFlush(Member.createGuardian(EMAIL, "encoded", "탈퇴전닉네임"));
        Long originalId = original.getId();

        memberService.withdraw(originalId);
        entityManager.flush();
        entityManager.clear();

        assertThatCode(() -> memberRepository.saveAndFlush(
                        Member.createGuardian(EMAIL, "encoded", "재가입닉네임")))
                .doesNotThrowAnyException();
        entityManager.clear();

        // 원래 이메일은 익명화된 탈퇴 회원이 아니라 방금 재가입한 새 회원만 가리켜야 한다
        // (@SQLRestriction 필터링 덕분에 탈퇴 회원은 애초에 조회 대상에서 빠진다).
        assertThat(memberRepository.existsByEmail(EMAIL)).isTrue();
        assertThat(memberRepository.findByEmail(EMAIL))
                .hasValueSatisfying(member -> {
                    assertThat(member.getId()).isNotEqualTo(originalId);
                    assertThat(member.getNickname()).isEqualTo("재가입닉네임");
                });
    }
}
