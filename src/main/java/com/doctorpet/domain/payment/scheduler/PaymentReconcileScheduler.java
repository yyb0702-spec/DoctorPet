package com.doctorpet.domain.payment.scheduler;

import com.doctorpet.domain.payment.service.PaymentReconcileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
  결제 정산 스케줄러(#35, SA §9-7). 5분 주기로 정산 배치를 트리거한다(주기는 설정값). 실제 처리·락·분기는
  PaymentReconcileService가 담당하고, 스케줄러는 트리거와 실행 요약 관측(로그)만 얇게 맡는다.
  @EnableScheduling은 SchedulerConfig에 있다. fixedDelay라 이전 실행이 끝난 뒤 주기를 센다(중첩 실행 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileScheduler {

    private final PaymentReconcileService paymentReconcileService;

    @Scheduled(
            fixedDelayString = "${payment.reconcile.interval-ms:300000}",
            initialDelayString = "${payment.reconcile.interval-ms:300000}"
    )
    public void runReconcile() {
        ReconcileSummary summary = paymentReconcileService.reconcile();
        if (!summary.locked()) {
            log.debug("결제 정산 배치 스킵(다른 인스턴스 실행 중)");
            return;
        }
        log.info("결제 정산 배치 완료: scanned={}, paid={}, offlineRequired={}, stillPending={}, errored={}",
                summary.scanned(), summary.paid(), summary.offlineRequired(),
                summary.stillPending(), summary.errored());
    }
}
