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
    // 주의: MEMBER_006은 feature/member 브랜치에서 MEMBER_NOT_FOUND로 이미 쓰고 있어 여기서는 007부터 잇는다.
    // 같은 회원의 재발급 요청이 이미 처리 중일 때(회원당 재발급 직렬화 락, 리뷰 지적 대응) — 클라이언트가
    // 잠시 후 다시 시도하면 되는 일시적 상태라 재로그인이 필요한 401 계열과 구분해 409로 응답한다.
    REISSUE_IN_PROGRESS(HttpStatus.CONFLICT, "MEMBER_007", "다른 재발급 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
