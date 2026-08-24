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
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_006", "존재하지 않는 회원입니다."),
    // 같은 회원의 재발급 요청이 이미 처리 중일 때(회원당 재발급 직렬화 락) — 클라이언트가 잠시 후
    // 다시 시도하면 되는 일시적 상태라 재로그인이 필요한 401 계열과 구분해 409로 응답한다.
    REISSUE_IN_PROGRESS(HttpStatus.CONFLICT, "MEMBER_007", "다른 재발급 요청이 처리 중입니다. 잠시 후 다시 시도해주세요."),
    // 활성 예약(CONFIRMED·CHECKED_IN) 또는 미수금(OFFLINE_REQUIRED) 보유 회원의 탈퇴 시도(SA §6-3,
    // 부록A 확정 — 탈퇴 보류 정책). 클라이언트가 예약을 취소·완료하거나 미수금을 정산한 뒤 다시
    // 탈퇴를 시도하면 되는 상태라 409로 응답한다.
    WITHDRAWAL_BLOCKED(HttpStatus.CONFLICT, "MEMBER_008", "활성 예약 또는 미수금이 있어 탈퇴할 수 없습니다. 예약을 취소·완료하거나 정산 후 다시 시도해주세요."),
    // 이메일 인증 전 로그인 시도(백로그 P2 — 가입 시 이메일 인증 필수). 계정 자체는 존재하고
    // 비밀번호도 맞지만, 인증 메일의 링크를 아직 클릭하지 않은 상태.
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "MEMBER_009", "이메일 인증이 필요합니다. 가입 시 발송된 메일의 링크를 확인해주세요."),
    // 이메일 인증·비밀번호 재설정 토큰이 없거나(오타·변조), 이미 사용됐거나, 만료된 경우를 모두
    // 포괄한다 — Redis에 저장된 토큰은 만료되면 키 자체가 사라져 "없음"과 "만료됨"을 구분할 수
    // 없으므로(MemberTokenRepository), 두 상황을 하나의 코드로 통일해 응답한다.
    INVALID_OR_EXPIRED_TOKEN(HttpStatus.BAD_REQUEST, "MEMBER_010", "유효하지 않거나 만료된 링크입니다. 다시 요청해주세요."),
    // 회원가입·이메일 인증 재발송·비밀번호 재설정 요청 - 비인증 상태에서 임의의 이메일로
    // 메일을 계속 발송시킬 수 있어(메일 폭탄, SES 등 발송 한도 소진) IP 기준으로 제한한다
    // (기능 구멍 점검 대응, AuthRateLimiter 참고).
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "MEMBER_011", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
