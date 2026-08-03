package com.doctorpet.global.gateway.payment.portone;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PortOne V2 연동 설정. 인증정보(apiSecret·webhookSecret·storeId)는 application-local.yml·환경변수로 주입하고
 * 커밋하지 않는다(보안 규칙). 타임아웃·재시도 수치는 팀 확정 기본값(이슈 #38)이며 배포 없이 설정으로 조정 가능하다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "payment.portone")
public class PortOneProperties {

    /** PortOne V2 API 베이스 URL(실연동 시 확정·주입). */
    private String baseUrl;

    /** API Secret(커밋 금지, 로그 마스킹 대상). */
    private String apiSecret;

    /** 상점 식별자(storeId). 공개 식별자라 비밀은 아니지만 값은 콘솔에서 확정·주입한다. */
    private String storeId;

    /**
     * 빌링키 결제(정기결제) 채널 식별자(channelKey). API 정기결제 승인 시 어느 채널로 청구할지 지정한다.
     * 프론트 결제창에서도 쓰는 값이라 비밀은 아니지만, 콘솔에서 발급한 결제(정기결제) 채널의 값을 주입한다(#57).
     */
    private String channelKey;

    /**
     * 웹훅 서명 검증용 시크릿(#48). PortOne 콘솔의 결제알림(Webhook)에서 발급한다.
     * 원문 body 서명 검증에만 쓰이며 API Secret과 별개다 — 커밋 금지, 로그 마스킹 대상.
     */
    private String webhookSecret;

    /** 연결 타임아웃(ms). 기본 2초. */
    private int connectTimeoutMs = 2000;

    /** 읽기 타임아웃(ms). 기본 5초. */
    private int readTimeoutMs = 5000;

    /** 재시도 유효 오류의 최대 재시도 횟수. 기본 3회(SA §9-4 확정). */
    private int maxRetry = 3;

    /** 지수 백오프 초기 대기(ms). 기본 500ms → 1s → 2s. */
    private long backoffInitialMs = 500L;
}
