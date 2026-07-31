package com.doctorpet.domain.member.dto.request;

import com.doctorpet.global.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 확인. SignupRequest.password와 같은 이유로 @Size가 아니라 @MaxUtf8Bytes(72)로
 * 검증한다(BCrypt는 UTF-8 바이트 수 기준 72바이트 제한).
 */
public record PasswordResetConfirmRequest(

        @NotBlank(message = "토큰은 필수입니다.")
        String token,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @MaxUtf8Bytes(value = 72, message = "비밀번호는 UTF-8 기준 72바이트를 초과할 수 없습니다.")
        String newPassword
) {
}
