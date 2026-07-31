package com.doctorpet.domain.member.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 이메일 하나만 받는 요청 — 이메일 인증 재발송(POST /api/auth/verify-email/resend), 비밀번호
 * 재설정 요청(POST /api/auth/password-reset/request) 양쪽에서 공용으로 쓴다(둘 다 계정 존재
 * 여부를 노출하지 않고 조용히 처리하므로 응답 형태도 동일하다).
 */
public record EmailRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email
) {
}
