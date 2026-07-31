package com.doctorpet.global.gateway.mail;

/*
  이메일 발송 게이트웨이. PaymentGateway(전역 결제 게이트웨이 추상화)와 동일한 패턴을 따른다 —
  구현체는 mail.provider 설정값에 따라 조건부로 하나만 등록된다(FakeEmailGateway/SmtpEmailGateway,
  MailConfig 참고). 인터페이스 자체는 특정 발송 수단(SMTP 등)을 몰라야 하므로 제목·본문만 받는다.
 */
public interface EmailGateway {

    void send(String to, String subject, String body);
}
