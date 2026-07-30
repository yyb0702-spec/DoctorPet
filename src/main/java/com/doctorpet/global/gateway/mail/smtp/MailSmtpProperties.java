package com.doctorpet.global.gateway.mail.smtp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/*
  실제 SMTP 발송 설정(mail.provider=smtp일 때만 사용). PortOneProperties(payment.portone.*)와
  같은 패턴 — @Getter @Setter로 두는 이유도 동일하다(ConfigurationProperties 바인딩).
  host/port/username/password는 기본값이 없다 — SMTP 계정 정보는 각자 로컬 환경에서
  application-local.yml에 채워 넣어야 하며 커밋하지 않는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mail.smtp")
public class MailSmtpProperties {

    private String host;
    private int port = 587;
    private String username;
    private String password;
}
