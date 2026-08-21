package com.doctorpet.global.gateway.mail.smtp;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/*
  실제 SMTP 발송 설정(mail.provider=smtp일 때만 사용). PortOneProperties(payment.portone.*)와
  같은 패턴 — @Getter @Setter로 두는 이유도 동일하다(ConfigurationProperties 바인딩).
  host/port/username/password는 기본값이 없다 — SMTP 계정 정보는 각자 로컬 환경에서
  application-local.yml에 채워 넣어야 하며 커밋하지 않는다.

  타임아웃 3종은 기본값을 둔다. JavaMail 기본값이 "무한 대기"라서, SMTP 호스트가 응답을 안 주는
  상태(보안그룹 오변경, 제공자 장애)가 되면 발송을 호출한 스레드가 영원히 붙잡힌다. 알림 이메일
  채널(고도화 3.9)은 예약 승인·결제 확정 요청이 커밋된 직후 그 요청 스레드에서 발송하므로, 값이
  없으면 그 API가 응답을 못 돌려주고 같은 리스너의 SSE 전달까지 함께 막힌다(리뷰 지적 P1).
  spring.data.redis.timeout을 명시한 것과 같은 이유다 — "즉시 거부"가 아니라 "조용히 블랙홀"이
  현실적인 실패 모드다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mail.smtp")
public class MailSmtpProperties {

    private String host;
    private int port = 587;
    private String username;
    private String password;
    // 커넥션 수립(TLS 핸드셰이크 포함). 커맨드 타임아웃보다 짧게 둔다.
    private Duration connectionTimeout = Duration.ofSeconds(5);
    // 응답 대기. 메일 발송은 사용자 요청 응답 경로에 있으므로 짧게 둔다.
    private Duration readTimeout = Duration.ofSeconds(10);
    // 본문 전송. 첨부가 없어 본문이 작으므로 읽기와 같은 값으로 둔다.
    private Duration writeTimeout = Duration.ofSeconds(10);
}
