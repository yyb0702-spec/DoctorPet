package com.doctorpet.domain.member.dto.request;

import com.doctorpet.global.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청. SA §8-1: {@code POST /api/auth/signup { email, password, nickname, phone }}.
 * 비밀번호 규칙(8자 이상, 문자 종류 제한 없음)은 A 도메인 결정 #2를 따른다.
 *
 * 각 필드에 상한을 둔 이유: 이게 없으면 members 테이블 컬럼 길이(기본 varchar(255))를 넘는 값이
 * Bean Validation을 통과해 DB까지 내려가고, 거기서 DataIntegrityViolationException이 발생한다 —
 * 이건 중복(1062)이 아니라 길이 초과라서 GlobalExceptionHandler가 500으로 분류해버려, 잘못된
 * 입력(400)이어야 할 게 서버 오류로 응답된다. email·nickname은 컬럼 길이(255)에 맞춘다.
 *
 * password는 @Size가 아니라 @MaxUtf8Bytes(72)로 검증한다 — BCrypt의 72 제한은 "문자 수"가 아니라
 * UTF-8 인코딩 후 "바이트 수" 기준이다. @Size(max = 72)를 쓰면 한글 25자(문자 수로는 72 이하)도
 * UTF-8로는 75바이트라 통과해버리고, 그 뒤 passwordEncoder.encode()에서 예외가 나 500으로
 * 이어진다(리뷰 지적) — @MaxUtf8Bytes가 실제 인코딩 바이트 수를 세어 이 문제를 막는다.
 */
public record SignupRequest(

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자를 초과할 수 없습니다.")
        String email,

        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, message = "비밀번호는 8자 이상이어야 합니다.")
        @MaxUtf8Bytes(value = 72, message = "비밀번호는 UTF-8 기준 72바이트를 초과할 수 없습니다.")
        String password,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 255, message = "닉네임은 255자를 초과할 수 없습니다.")
        String nickname,

        // 병원이 예약 확인·노쇼 직전 연락을 할 수단이 없던 문제(기능 구멍 점검) 대응으로 추가.
        // 하이픈 유무 둘 다 허용한다(010-1234-5678 / 01012345678). 010·011·016~019만 허용.
        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^01(?:0|1|[6-9])-?\\d{3,4}-?\\d{4}$", message = "전화번호 형식이 올바르지 않습니다. (예: 010-1234-5678)")
        String phone
) {
}
