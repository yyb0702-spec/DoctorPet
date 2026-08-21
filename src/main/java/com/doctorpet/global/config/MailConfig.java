package com.doctorpet.global.config;

import com.doctorpet.global.gateway.mail.smtp.MailSmtpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/*
  mail.provider=smtp일 때만 실제 JavaMailSender 빈을 등록한다(payment.gateway와 동일한
  fail-safe 관례 — mail.provider가 비어 있거나 fake면 이 빈도, FakeEmailGateway가 아닌
  실제 발송기도 등록되지 않는다).
 */
@Configuration
@EnableConfigurationProperties(MailSmtpProperties.class)
public class MailConfig {

    @Bean
    @ConditionalOnProperty(name = "mail.provider", havingValue = "smtp")
    public JavaMailSender javaMailSender(MailSmtpProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());

        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.put("mail.transport.protocol", "smtp");
        javaMailProperties.put("mail.smtp.auth", "true");
        javaMailProperties.put("mail.smtp.starttls.enable", "true");
        // JavaMail 기본값은 무한 대기다 — 값을 주지 않으면 응답 없는 SMTP 호스트가 발송 호출 스레드를
        // 영원히 붙잡는다. 알림 이메일 채널이 예약 승인·결제 확정 요청 스레드에서 발송하므로 필수다
        // (리뷰 지적 P1, 근거는 MailSmtpProperties 주석).
        javaMailProperties.put("mail.smtp.connectiontimeout",
                String.valueOf(properties.getConnectionTimeout().toMillis()));
        javaMailProperties.put("mail.smtp.timeout",
                String.valueOf(properties.getReadTimeout().toMillis()));
        javaMailProperties.put("mail.smtp.writetimeout",
                String.valueOf(properties.getWriteTimeout().toMillis()));

        return sender;
    }
}
