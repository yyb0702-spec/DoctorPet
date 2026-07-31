package com.doctorpet.global.gateway.mail.fake;

import com.doctorpet.global.gateway.mail.EmailGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/*
  로컬/테스트용 가짜 발송기. PaymentGateway의 FakePaymentGateway와 같은 이유로 존재한다 —
  mail.provider를 설정하지 않으면 이 빈도 SmtpEmailGateway도 등록되지 않아(fail-safe), 실수로
  아무 발송기도 없는 채 서비스가 이메일 발송 지점에서 NoSuchBeanDefinitionException으로 즉시
  실패하게 만든다(운영에서 조용히 "발송된 것처럼" 넘어가는 상황 자체를 차단).
  실제 발송 대신 로그로 남긴다 — 로컬 개발 시 콘솔에서 인증 링크·재설정 링크를 바로 확인할 수 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mail.provider", havingValue = "fake")
public class FakeEmailGateway implements EmailGateway {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[FAKE MAIL] to={}, subject={}\n{}", to, subject, body);
    }
}
