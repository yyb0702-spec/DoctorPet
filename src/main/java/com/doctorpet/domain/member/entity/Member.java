package com.doctorpet.domain.member.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/*
  회원. SA §4 members 테이블 스펙을 따른다.
  탈퇴는 Soft Delete(deleted_at)이며, 활성 회원({@code deleted_at IS NULL}) 기준으로만
  조회되도록 {@link SQLRestriction}을 적용한다(SA §6-3).
 */
@Getter
@Entity
@Table(
        name = "members",
        // 이름을 명시하지 않으면 Hibernate가 버전에 따라 다른 이름을 생성해 DB 오류 메시지에서
        // 제약을 식별하기 어렵다. GlobalExceptionHandler가 이 이름 대신 벤더 오류 코드(MySQL 1062)로
        // 중복 여부를 판별하긴 하지만, 제약 자체는 이름을 고정해두는 편이 디버깅에 유리하다.
        uniqueConstraints = @UniqueConstraint(name = "uk_members_email", columnNames = "email")
)
@SQLRestriction("deleted_at is null")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Column(name = "hospital_id")
    private Long hospitalId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 이메일 인증 여부(백로그 P2, 회원가입 시 이메일 소유 확인 — 이메일 인증 인프라 구축 시 추가).
     * 가입 직후에는 false이며, 이 값이 true가 될 때까지 로그인이 차단된다(AuthService.login()).
     * ddl-auto=update로 기존 행에도 안전하게 컬럼이 추가되도록 columnDefinition에 DEFAULT 0을 둔다.
     */
    @Column(name = "email_verified", nullable = false, columnDefinition = "tinyint(1) not null default 0")
    private boolean emailVerified;

    /**
     * 로그인 실패 잠금 정책(A 도메인 결정 #1). SA §4엔 없는 컬럼으로, 로그인 기능 구현 시 새로 추가했다.
     * 5회 연속 실패 시 30분 잠금, 30분 경과 시 자동 해제(실패 횟수도 함께 초기화).
     */
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    private static final long LOCK_DURATION_MINUTES = 30;

    private Member(String email, String password, String nickname, MemberRole role, Long hospitalId) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.role = role;
        this.hospitalId = hospitalId;
        this.emailVerified = false;
        this.failedLoginAttempts = 0;
    }

    /**
     * 보호자(GUARDIAN) 회원가입. 병원 스태프(HOSPITAL_STAFF)는 회원가입 대상이 아니라
     * 제휴 병원 시드 데이터로 생성된다(SA §6-2) — 이 팩토리로 만들 수 없다.
     */
    public static Member createGuardian(String email, String password, String nickname) {
        return new Member(email, password, nickname, MemberRole.GUARDIAN, null);
    }

    /** 잠금 시간이 지났으면 자동 해제한다(실패 횟수 초기화 포함). 로그인 시도마다 가장 먼저 호출한다. */
    public void unlockIfExpired(LocalDateTime now) {
        if (lockedUntil != null && !now.isBefore(lockedUntil)) {
            this.lockedUntil = null;
            this.failedLoginAttempts = 0;
        }
    }

    public boolean isLocked() {
        return lockedUntil != null;
    }

    /** 로그인 실패 기록. 임계값(5회) 도달 시 잠금을 건다. */
    public void recordLoginFailure(LocalDateTime now) {
        this.failedLoginAttempts++;
        if (this.failedLoginAttempts >= MAX_FAILED_LOGIN_ATTEMPTS) {
            this.lockedUntil = now.plusMinutes(LOCK_DURATION_MINUTES);
        }
    }

    /** 로그인 성공. 실패 기록·잠금을 모두 초기화한다. */
    public void recordLoginSuccess() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /**
     * 이메일 인증 완료 처리(백로그 P2). 이미 인증된 회원에게 다시 호출해도 안전하도록(멱등)
     * 단순 대입만 한다 — 호출 전 토큰 유효성 검증은 EmailVerificationService의 책임이다.
     */
    public void verifyEmail() {
        this.emailVerified = true;
    }

    /**
     * 닉네임 변경(프로필 수정). email·password는 각각 별도 흐름(이메일은 재가입 정책과
     * 얽혀 있고, password는 인증·재설정 흐름이 따로 있다)으로 다루므로 여기서는 취급하지 않는다 —
     * 프로필 수정 범위는 닉네임으로 한정한다(A 도메인 결정).
     */
    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 비밀번호 재설정(백로그 P2). SA엔 "재설정 성공 시 잠금 해제"라는 효과만 정의돼 있었는데,
     * 실제 재설정 메커니즘(이메일 링크형 토큰)을 이번에 구현하며 그 효과를 여기서 반영한다 — 새
     * 비밀번호로 교체함과 동시에 로그인 실패 기록·잠금도 초기화한다. 잠긴 계정이라면 재설정 자체가
     * 곧 잠금 해제 수단이 된다.
     */
    public void resetPassword(String encodedPassword) {
        this.password = encodedPassword;
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /*
      탈퇴(Soft Delete) + 이메일 익명화(SA §6-3, 부록A 확정). email을 `withdrawn_{id}@deleted.doctorpet`로
      치환해 `email UNIQUE` 제약을 유지한 채 탈퇴 후 동일 이메일 재가입을 허용한다.
      활성 예약(CONFIRMED·CHECKED_IN)·미수금(OFFLINE_REQUIRED) 보유 여부 확인은 이 메서드의 책임이
      아니다 — Reservation/Payment 도메인 Repository를 여기서 직접 참조할 수 없으므로(구현
      가드레일), 상위 레이어(MemberWithdrawalApplicationService)가 각 도메인 Service를 통해
      먼저 확인하고 통과한 경우에만 이 메서드를 호출해야 한다.
     */
    public void withdraw(LocalDateTime now) {
        this.email = "withdrawn_" + this.id + "@deleted.doctorpet";
        this.deletedAt = now;
    }
}
