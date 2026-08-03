package com.doctorpet.domain.payment.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기본 청구 감사 기록 구현. 별도 감사 테이블(DDL·보존정책은 정책 결정 대상)을 두지 않고, 저장소 관례대로
 * 구조화된 감사 로그 한 줄을 남긴다(중앙 로그 수집이 감사 추적 역할). 향후 영속 감사 저장소가 필요해지면
 * {@link PaymentChargeAuditLogger} 빈을 교체한다.
 *
 * <p>남기는 값은 모두 비민감 스칼라(스태프 id·예약 id·결제 id·금액)다 — 빌링키·카드번호 원본은 절대 남기지 않는다.
 * "AUDIT" 마커로 시작해 로그 파이프라인에서 감사 라인만 선별할 수 있게 한다.
 */
@Slf4j
@Component
public class Slf4jPaymentChargeAuditLogger implements PaymentChargeAuditLogger {

    @Override
    public void recordChargeAccepted(Long staffMemberId, Long reservationId, Long paymentId, int amount) {
        log.info("AUDIT 진료비 청구 접수: staffMemberId={}, reservationId={}, paymentId={}, amount={}",
                staffMemberId, reservationId, paymentId, amount);
    }
}
