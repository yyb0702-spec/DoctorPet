package com.doctorpet.domain.member.adapter;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/*
  결제 도메인의 StaffHospitalPort(스태프 소속 병원 해석) 구현. 헥사고날 — 소비자(payment)가 선언한 계약을
  제공자(member)가 구현한다. member 내부 Repository를 직접 열지 않고 MemberService.getMyInfo를 경유해
  가드레일("다른 도메인은 Service 경유")을 지키며, 예약 운영(#27, PR #74)의 자병원 검증과 동일한 방식을 쓴다.

  role==HOSPITAL_STAFF && hospitalId!=null 일 때만 소속 병원을 반환하고, 아니면 빈 Optional(→ 결제 서비스가
  FORBIDDEN_HOSPITAL로 처리)로 귀결시킨다. Member는 @SQLRestriction으로 탈퇴 회원이 조회되지 않으므로
  탈퇴 스태프는 getMyInfo에서 MEMBER_NOT_FOUND로 걸러진다.
  향후 auth가 MemberPrincipal에 hospitalId를 반영하면 이 구현만 교체하면 된다.
 */
@Component
@RequiredArgsConstructor
public class MemberStaffHospitalAdapter implements StaffHospitalPort {

    private final MemberService memberService;

    @Override
    public Optional<Long> findHospitalIdByMemberId(Long memberId) {
        MemberResponse member = memberService.getMyInfo(memberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            return Optional.empty();
        }
        return Optional.of(member.hospitalId());
    }
}
