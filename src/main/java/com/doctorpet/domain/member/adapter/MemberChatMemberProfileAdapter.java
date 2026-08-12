package com.doctorpet.domain.member.adapter;

import com.doctorpet.domain.chat.port.ChatMemberProfilePort;
import com.doctorpet.domain.chat.exception.ChatErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberChatMemberProfileAdapter implements ChatMemberProfilePort {

    private final MemberService memberService;

    @Override
    public String getGuardianNickname(Long memberId) {
        MemberResponse member = memberService.getMyInfo(memberId);
        if (member.role() != MemberRole.GUARDIAN) {
            throw new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        return member.nickname();
    }
}
