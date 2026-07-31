package com.doctorpet.domain.member.event;

import com.doctorpet.domain.member.entity.Member;

/**
 * 회원가입 완료 이벤트. AuthService.signup()의 DB 트랜잭션 커밋 이후에만 인증 메일 발송을
 * 트리거하기 위해 존재한다(리뷰 지적 — 예전에는 signup()의 @Transactional 안에서 곧바로
 * EmailVerificationService.sendVerificationEmail()을 호출했는데, 이러면 (1) SMTP가 느릴 때
 * DB 트랜잭션·커넥션을 필요 이상으로 오래 붙들고, (2) 극단적으로는 커밋 전에 인증 링크가
 * 클릭되면 verifyEmail()이 아직 다른 트랜잭션에 보이지 않는 회원을 못 찾아 MEMBER_NOT_FOUND를
 * 던지면서, 1회용 토큰은 이미 소비돼버려 그 링크가 영구적으로 재사용 불가능해진다).
 *
 * member는 signup() 트랜잭션 안에서 막 save()한 엔티티를 그대로 담는다 — 리스너가 접근하는
 * getId()/getEmail()은 지연 로딩 연관관계가 아닌 단순 스칼라 필드라, 트랜잭션·영속성 컨텍스트가
 * 이미 끝난 뒤(AFTER_COMMIT)에 읽어도 LazyInitializationException 걱정 없이 안전하다.
 */
public record MemberSignedUpEvent(Member member) {
}
