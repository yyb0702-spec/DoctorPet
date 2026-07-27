package com.doctorpet.domain.member.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "MEMBER_001", "이미 사용 중인 이메일입니다."),
    // 이메일 미존재/비밀번호 불일치를 구분하지 않고 같은 코드로 응답한다(계정 존재 여부 노출 방지).
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MEMBER_002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED, "MEMBER_003", "로그인 실패 횟수를 초과해 계정이 잠겼습니다. 30분 후 다시 시도하거나 비밀번호를 재설정하세요."),
    // 서명 위조/만료/형식 오류 등 토큰 자체가 유효하지 않은 경우.
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "MEMBER_004", "유효하지 않은 토큰입니다. 다시 로그인해주세요."),
    // 토큰 서명은 유효하지만 Redis 화이트리스트와 일치하지 않는 경우 — 이미 회전(rotate)되어 폐기된 토큰의
    // 재사용으로 간주하고 세션 전체를 무효화한다(SA §6-1 재사용 감지, A 도메인 결정 #6).
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "MEMBER_005", "이미 사용된 토큰입니다. 다시 로그인해주세요."),
    // Access Token은 유효하지만(만료 전) 그 사이 탈퇴 등으로 회원이 존재하지 않는 좁은 race condition 대비.
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_006", "존재하지 않는 회원입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
