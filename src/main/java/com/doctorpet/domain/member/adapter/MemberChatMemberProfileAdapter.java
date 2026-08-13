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
        MemberResponse member = memberService.findActiveMemberProfile(memberId)
                // 채팅은 탈퇴 후에도 1년간 보존한다. Member의 @SQLRestriction으로 탈퇴 보호자가
                // 조회되지 않아도 과거 메시지 이력이 500/404나 rollback으로 끊기지 않게 한다.
                .orElse(null);
        if (member == null) {
            return "탈퇴한 보호자";
        }
        if (member.role() != MemberRole.GUARDIAN) {
            throw new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        return member.nickname();
    }
}
