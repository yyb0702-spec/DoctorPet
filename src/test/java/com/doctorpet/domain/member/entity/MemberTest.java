package com.doctorpet.domain.member.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Member 엔티티 단위 테스트. withdraw()는 Service/Repository 없이도 검증 가능한 순수 도메인
 * 로직이라 다른 도메인(Reservation/Payment)의 조회 기능 완성을 기다리지 않고 먼저 작성한다
 * (부록A #4 정책은 이미 확정, 크로스도메인 사전 검증은 상위 레이어의 책임 — Member.withdraw()
 * 자체의 계약이 아니다).
 */
class MemberTest {

    @Test
    @DisplayName("withdraw()는 email을 withdrawn_{id}@deleted.doctorpet로 치환하고 deletedAt을 설정한다(SA §6-3)")
    void withdraw_anonymizesEmailAndSetsDeletedAt() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        setId(member, 42L);
        LocalDateTime now = LocalDateTime.now();

        member.withdraw(now);

        assertThat(member.getEmail()).isEqualTo("withdrawn_42@deleted.doctorpet");
        assertThat(member.getDeletedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("생성 직후에는 이메일 인증이 안 된 상태다(백로그 P2 — 가입 시 이메일 인증 필수)")
    void createGuardian_startsUnverified() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");

        assertThat(member.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("createGuardian(3-arg)는 phone=null로 생성하고, 4-arg는 전달받은 phone을 그대로 저장한다(기능 구멍 점검 대응)")
    void createGuardian_phoneOverload() {
        Member withoutPhone = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");
        Member withPhone = Member.createGuardian("guardian2@example.com", "encoded-password", "보호자닉네임", "010-1234-5678");

        assertThat(withoutPhone.getPhone()).isNull();
        assertThat(withPhone.getPhone()).isEqualTo("010-1234-5678");
    }

    @Test
    @DisplayName("verifyEmail()을 호출하면 인증 완료 상태가 된다")
    void verifyEmail_marksVerified() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "보호자닉네임");

        member.verifyEmail();

        assertThat(member.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("updateNickname()은 닉네임만 교체한다(프로필 수정 범위 — 이메일/비밀번호는 대상 아님)")
    void updateNickname_replacesNicknameOnly() {
        Member member = Member.createGuardian("guardian@example.com", "encoded-password", "옛닉네임");

        member.updateNickname("새닉네임");

        assertThat(member.getNickname()).isEqualTo("새닉네임");
        assertThat(member.getEmail()).isEqualTo("guardian@example.com");
        assertThat(member.getPassword()).isEqualTo("encoded-password");
    }

    @Test
    @DisplayName("resetPassword()는 비밀번호를 교체하고 로그인 실패 기록·잠금을 초기화한다(SA \"재설정 성공 시 잠금 해제\")")
    void resetPassword_replacesPasswordAndClearsLockout() {
        Member member = Member.createGuardian("guardian@example.com", "old-encoded-password", "보호자닉네임");
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 5; i++) {
            member.recordLoginFailure(now);
        }
        assertThat(member.isLocked()).isTrue();

        member.resetPassword("new-encoded-password");

        assertThat(member.getPassword()).isEqualTo("new-encoded-password");
        assertThat(member.isLocked()).isFalse();
        assertThat(member.getFailedLoginAttempts()).isZero();
    }

    private void setId(Member member, Long id) {
        try {
            var field = Member.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(member, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
