package com.doctorpet.global.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** application-local.yml 등에서 주입되는 서명 비밀키 (커밋 금지) */
    private String secret;

    /** Access Token 만료 시간 (ms) */
    private long accessTokenExpiration;

    /** Refresh Token 만료 시간 (ms) */
    private long refreshTokenExpiration;
}
