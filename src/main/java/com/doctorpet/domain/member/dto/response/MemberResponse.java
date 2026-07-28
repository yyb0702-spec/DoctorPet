package com.doctorpet.domain.member.dto.response;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;

/**
 * 내 정보 조회 응답. SA §8-1: {@code GET /api/members/me} → 200.
 */
public record MemberResponse(
        Long memberId,
        String email,
        String nickname,
        MemberRole role,
        Long hospitalId
) {

    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getEmail(),
                member.getNickname(),
                member.getRole(),
                member.getHospitalId()
        );
    }
}
