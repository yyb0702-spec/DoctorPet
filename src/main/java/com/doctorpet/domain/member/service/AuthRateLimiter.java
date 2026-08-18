package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.config.AuthRateLimitProperties;
import com.doctorpet.domain.member.exception.AuthRateLimitStorageException;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.AuthRateLimitRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 회원가입·이메일 인증 재발송·비밀번호 재설정 요청 rate limit(기능 구멍 점검 대응) — 셋 다
 * 인증 없이 호출 가능하고 임의의 이메일로 메일을 발송시키는데, 이전까지는 아무 제한이 없어
 * 메일 폭탄(제3자 이메일 스팸 신고 유발)이나 SES 등 발송 한도 소진으로 이어질 수 있었다.
 *
 * IP 기준으로 제한하는 이유 — 계정 존재 여부를 노출하지 않는 이 도메인의 기존 계약
 * (PasswordResetService·EmailVerificationService의 "조용히 반환" 원칙)과 마찬가지로, 이메일
 * 기준으로 제한하면 "이 이메일이 이미 몇 번 시도됐다"는 사실 자체가 계정 존재 여부의 곁가지
 * 정보가 될 수 있다. IP 기준은 그 문제가 없다.
 *
 * domain.ai.service.AiRateLimiter와 같은 이유로 저장소(Redis) 장애 시 fail-open(허용)한다 —
 * 이 rate limit은 어뷰징 억제용 방어선이지, 없으면 서비스가 멈춰야 하는 필수 기능이 아니다.
 * Redis가 이미 내려간 상태라면 이 세 엔드포인트가 의존하는 토큰 발급(MemberTokenRepository)도
 * 어차피 실패하므로, rate limit만 fail-closed로 만들어봤자 실질적인 추가 보호는 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthRateLimiter {

    private static final String KEY_PREFIX = "auth:rate-limit:";

    private final AuthRateLimitRepository repository;
    private final AuthRateLimitProperties properties;
    private final ClientIpResolver clientIpResolver;

    public void check(HttpServletRequest request, AuthRateLimitAction action) {
        String clientIp = clientIpResolver.resolve(request, Set.copyOf(properties.getTrustedProxies()));
        int limit = limitFor(action);

        long count;
        try {
            count = repository.increment(
                    KEY_PREFIX + action.name() + ":ip:" + hash(clientIp),
                    properties.getWindow()
            );
        } catch (AuthRateLimitStorageException exception) {
            log.warn("인증메일 rate limit 저장소 처리에 실패해 이번 요청은 통과시킵니다. action={}", action, exception);
            return;
        }

        if (count > limit) {
            throw new ServiceException(MemberErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    private int limitFor(AuthRateLimitAction action) {
        return switch (action) {
            case SIGNUP -> properties.getSignupPerIpPerHour();
            case VERIFY_EMAIL_RESEND -> properties.getVerifyEmailResendPerIpPerHour();
            case PASSWORD_RESET_REQUEST -> properties.getPasswordResetRequestPerIpPerHour();
        };
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
