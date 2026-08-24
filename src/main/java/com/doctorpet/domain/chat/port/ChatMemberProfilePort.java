package com.doctorpet.domain.chat.port;

/** 채팅 응답의 보호자 표시명을 서버 측 회원 정보로 해석하는 계약이다. */
public interface ChatMemberProfilePort {

    String getGuardianNickname(Long memberId);
}
