package com.doctorpet.domain.member.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청. SA §8-1: {@code POST /api/auth/signup { email, password, nickname }}.
 * 비밀번호 규칙(8자 이상, 문자 종류 제한 없음)은 A 도메인 결정 #2를 따른다.
 *
 * 각 필드에 상한(@Size max)을 둔 이유: 이게 없으면 members 테이블 컬럼 길이(기본 varchar(255))를
 * 넘는 값이 Bean Validation을 통과해 DB까지 내려가고, 거기서 DataIntegrityViolationException이
 * 발생한다 — 이건 중복(1062)이 아니라 길이 초과라서 GlobalExceptionHandler가 500으로 분류해버려,
 * 잘못된 입력(400)이어야 할 게 서버 오류로 응답된다. email·nickname은 컬럼 길이(255)에 맞추고,
 * password는 BCrypt가 실제로 처리하는 최대 바이트 수(72)로 제한한다 — 그 이상은 어차피 해시
 * 결과에 반영되지 않을뿐더러, 과도하게 긴 원문을 매번 해싱하는 비용(DoS 여지)도 막을 수 있다.
 */
public record SignupRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자를 초과할 수 없습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 72, message = "비밀번호는 8자 이상 72자 이하여야 합니다.")
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 255, message = "닉네임은 255자를 초과할 수 없습니다.")
        String nickname
) {
}
