package com.doctorpet.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.global.gateway.mail.smtp.MailSmtpProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Level 1 — SMTP 발송기에 타임아웃 3종이 실제로 실린다(리뷰 지적 P1).
 *
 * <p>이 검증이 따로 필요한 이유: 로컬·CI는 모두 `mail.provider=fake`라 {@link MailConfig#javaMailSender}가 아예
 * 등록되지 않는다. 그래서 통합 테스트를 아무리 돌려도 이 설정이 한 번도 실행되지 않고, 타임아웃이 빠져도
 * 아무 테스트가 깨지지 않는다 — JavaMail 기본값은 무한 대기라서, 빠지면 응답 없는 SMTP 호스트가 예약 승인·결제
 * 확정 요청 스레드를 영원히 붙잡는다(알림 이메일 채널은 그 요청 스레드에서 발송한다).
 */
class MailConfigTest {

    @Test
    @DisplayName("커넥션·읽기·쓰기 타임아웃이 밀리초로 JavaMail 속성에 실린다")
    void javaMailSender_carriesTimeouts() {
        MailSmtpProperties properties = new MailSmtpProperties();
        properties.setHost("smtp.example.com");
        properties.setConnectionTimeout(Duration.ofSeconds(3));
        properties.setReadTimeout(Duration.ofSeconds(7));
        properties.setWriteTimeout(Duration.ofSeconds(9));

        JavaMailSenderImpl sender = (JavaMailSenderImpl) new MailConfig().javaMailSender(properties);

        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.connectiontimeout", "3000")
                .containsEntry("mail.smtp.timeout", "7000")
                .containsEntry("mail.smtp.writetimeout", "9000");
    }

    @Test
    @DisplayName("타임아웃 기본값이 무한이 아니다")
    void defaultTimeouts_areBounded() {
        // 값을 주지 않은 환경(운영 yaml에 mail.smtp.*-timeout이 없는 경우)에서도 무한 대기가 되지 않아야 한다.
        JavaMailSenderImpl sender =
                (JavaMailSenderImpl) new MailConfig().javaMailSender(new MailSmtpProperties());

        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"))
                .isNotNull()
                .isNotEqualTo("0")
                .isNotEqualTo("-1");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.timeout"))
                .isNotNull()
                .isNotEqualTo("0")
                .isNotEqualTo("-1");
        assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.writetimeout"))
                .isNotNull()
                .isNotEqualTo("0")
                .isNotEqualTo("-1");
    }
}
