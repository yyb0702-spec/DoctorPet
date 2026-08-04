package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.PaymentWebhook;
import org.springframework.data.jpa.repository.JpaRepository;

/*
  결제 웹훅 수신 기록 저장소(#48). 멱등 판별용 존재 확인과, 경쟁 시 UNIQUE 위반으로 최종 방어한다.
 */
public interface PaymentWebhookRepository extends JpaRepository<PaymentWebhook, Long> {

    boolean existsByPaymentIdAndEventType(Long paymentId, String eventType);
}
