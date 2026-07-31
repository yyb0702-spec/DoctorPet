package com.doctorpet.global.gateway.mail.smtp;

import com.doctorpet.global.gateway.mail.EmailGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/*
  실제 SMTP 발송기. JavaMailSender 빈은 MailConfig에서 mail.smtp.* 설정으로 직접 구성한다
  (Spring Boot의 spring.mail.* 자동 구성 대신 이 프로젝트의 payment.* 관례처럼 자체 네임스페이스
  아래 설정을 두기 위함).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mail.provider", havingValue = "smtp")
public class SmtpEmailGateway implements EmailGateway {

    private final JavaMailSender javaMailSender;

    @Value("${mail.from}")
    private String from;

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        javaMailSender.send(message);
    }
}
