package com.doctorpet.domain.chat.port;

import java.util.Optional;

/** 채팅에서 인증된 병원 스태프의 소속 병원을 서버 측에서 해석하는 계약이다. */
public interface ChatStaffHospitalPort {

    Optional<Long> findHospitalIdByMemberId(Long memberId);
}
