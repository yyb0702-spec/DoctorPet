package com.doctorpet.domain.payment.port;

import java.util.Optional;

/**
 * 병원 스태프의 소속 병원 해석(port). 자병원 권한 검증에 필요한 스태프의 hospitalId를 얻는다.
 *
 * <p>배경: JWT·{@code MemberPrincipal}에는 현재 hospitalId가 없어(구현 공백), 인증 주체(memberId)로
 * 소속 병원을 서버에서 재해석해야 한다. 결제 도메인은 member Repository를 직접 호출하지 않고 이 계약에만
 * 의존하며(가드레일), member 도메인이 이를 구현한 어댑터를 제공한다.
 *
 * <p>향후 auth 도메인이 {@code MemberPrincipal}에 hospitalId를 반영하면(문서상 원래 의도), 이 구현만
 * principal 참조로 교체하면 되고 결제 도메인 코드는 바뀌지 않는다.
 */
public interface StaffHospitalPort {

    /**
     * 스태프 회원의 소속 병원 id를 반환한다. 병원 소속이 없으면(보호자 등) {@link Optional#empty()}.
     */
    Optional<Long> findHospitalIdByMemberId(Long memberId);
}
