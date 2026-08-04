package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.PaymentWebhook;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/*
  결제 웹훅 수신 기록 저장소(#48). webhook_id로 멱등 판별하고(같은 이벤트 재전송 흡수), 경쟁 시
  UNIQUE(webhook_id) 위반으로 최종 방어한다.
 */
public interface PaymentWebhookRepository extends JpaRepository<PaymentWebhook, Long> {

    Optional<PaymentWebhook> findByWebhookId(String webhookId);
}
