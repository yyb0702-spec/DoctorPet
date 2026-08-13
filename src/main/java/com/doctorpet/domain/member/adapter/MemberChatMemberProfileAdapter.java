package com.doctorpet.domain.member.adapter;

import com.doctorpet.domain.chat.port.ChatMemberProfilePort;
import com.doctorpet.domain.chat.exception.ChatErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.exception.MemberErrorCode;
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
        MemberResponse member;
        try {
            member = memberService.getMyInfo(memberId);
        } catch (ServiceException exception) {
            // 채팅은 탈퇴 후에도 1년간 보존한다. Member의 @SQLRestriction으로 탈퇴 보호자가
            // 조회되지 않아도 과거 메시지 이력이 500/404로 끊기지 않도록 고정된 안전 표시명을 쓴다.
            if (exception.getErrorCode() == MemberErrorCode.MEMBER_NOT_FOUND) {
                return "탈퇴한 보호자";
            }
            throw exception;
        }
        if (member.role() != MemberRole.GUARDIAN) {
            throw new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        return member.nickname();
    }
}
