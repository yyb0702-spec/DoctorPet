package com.doctorpet.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Redis 등 저장소에 토큰 원문 대신 넣을 SHA-256 해시를 만든다(백로그 #123).
 *
 * 대상은 Refresh Token(domain.member.repository.RefreshTokenRepository)과 이메일 인증·비밀번호
 * 재설정 토큰(domain.member.repository.MemberTokenRepository)이다. 두 경우 모두 "저장된 값(또는
 * 키)을 아는 것 자체가 곧 그 세션·그 검증 링크를 그대로 쓸 수 있다는 뜻"이라는 공통점이 있다 —
 * 즉 저장소 값 자체가 자격증명이다. 원문을 그대로 저장/키로 쓰면 저장소가 노출되는 사고(백업 유출,
 * 오설정으로 인한 외부 접근, 모니터링 도구의 KEYS/SCAN 등)만으로 서명 검증이나 이메일 수신 없이도
 * 곧바로 세션 탈취·계정 탈취가 가능해진다. 해시를 저장하면 그 사고가 나도 원문을 복원할 방법이 없다.
 *
 * salt/pepper를 쓰지 않는 이유: 비밀번호와 달리 이 토큰들은 전부 서버가 임의로 생성하는 고엔트로피
 * 값(JWT 서명 포함, 또는 UUID)이라 레인보우테이블·사전 대입 공격 표면이 없고, 원문을 오프라인으로
 * 추측해 시도할 수 있는 통로도 없다(재발급은 JWT 서명 검증을, 링크 소비는 이메일 수신을 항상 먼저
 * 거쳐야 한다). 그래서 비밀번호에 쓰는 느린 해시(BCrypt 등)가 아니라 빠른 SHA-256으로 충분하다 —
 * 오히려 매 요청 조회(재발급, 링크 클릭)마다 느린 해시를 쓰면 불필요한 지연만 늘어난다.
 */
public final class TokenHasher {

    private static final String ALGORITHM = "SHA-256";

    private TokenHasher() {
    }

    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 필수로 제공하는 알고리즘(JCA 표준 알고리즘 이름)이라
            // 실제로는 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
