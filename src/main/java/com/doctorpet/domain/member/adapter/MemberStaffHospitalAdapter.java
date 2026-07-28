package com.doctorpet.domain.member.adapter;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/*
  결제 도메인의 StaffHospitalPort(스태프 소속 병원 해석) 구현. 헥사고날 — 소비자(payment)가 선언한 계약을
  제공자(member)가 구현한다. member는 자기 Repository만 사용하므로 "다른 도메인 Repository 직접 호출 금지"
  가드레일에 걸리지 않고, payment는 member 내부를 모른 채 port로만 의존한다.

  Member는 @SQLRestriction("deleted_at is null")이라 탈퇴 회원은 조회되지 않는다(빈 Optional → 권한 거부).
  보호자(hospitalId=null)도 빈 Optional로 귀결돼 청구 권한이 없다.
  향후 auth가 MemberPrincipal에 hospitalId를 반영하면(문서상 의도) 이 구현만 교체하면 된다.
 */
@Component
@RequiredArgsConstructor
public class MemberStaffHospitalAdapter implements StaffHospitalPort {

    private final MemberRepository memberRepository;

    @Override
    public Optional<Long> findHospitalIdByMemberId(Long memberId) {
        return memberRepository.findById(memberId).map(Member::getHospitalId);
    }
}
