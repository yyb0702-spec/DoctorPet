package com.doctorpet.domain.member.dto.response;

/**
 * 로그인 응답. SA §8-1: {@code 200 { accessToken, refreshToken }}.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken
) {

    public static LoginResponse of(String accessToken, String refreshToken) {
        return new LoginResponse(accessToken, refreshToken);
    }
}
