package com.doctorpet.domain.member.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 재발급 요청. SA §8-1: {@code POST /api/auth/reissue { refreshToken }}.
 */
public record ReissueRequest(

        @NotBlank(message = "Refresh Token은 필수입니다.")
        String refreshToken
) {
}
