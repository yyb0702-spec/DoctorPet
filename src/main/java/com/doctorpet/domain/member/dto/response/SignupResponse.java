package com.doctorpet.domain.member.dto.response;

import com.doctorpet.domain.member.entity.Member;

/**
 * 회원가입 응답. SA §8-1: {@code 201 { memberId }}.
 */
public record SignupResponse(
        Long memberId
) {

    public static SignupResponse from(Member member) {
        return new SignupResponse(member.getId());
    }
}
