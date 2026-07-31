package com.doctorpet.domain.member.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 프로필 수정 요청. {@code PATCH /api/members/me { nickname } }.
 * 수정 범위는 닉네임으로 한정한다 — email(재가입 정책과 얽힘)·password(별도 인증/재설정 흐름)는
 * 이 API의 대상이 아니다(A 도메인 결정).
 */
public record NicknameUpdateRequest(

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 255, message = "닉네임은 255자를 초과할 수 없습니다.")
        String nickname
) {
}
