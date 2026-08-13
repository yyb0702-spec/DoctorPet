package com.doctorpet.domain.member.adapter;

import com.doctorpet.domain.chat.port.ChatStaffHospitalPort;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberChatStaffHospitalAdapter implements ChatStaffHospitalPort {

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
