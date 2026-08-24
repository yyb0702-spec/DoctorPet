package com.doctorpet.domain.payment.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기본 청구 감사 기록 구현. 별도 감사 테이블(DDL·보존정책은 정책 결정 대상)을 두지 않고, 저장소 관례대로
 * 구조화된 감사 로그 한 줄을 남긴다. 향후 영속 감사 저장소(테이블)가 필요해지면 {@link PaymentChargeAuditLogger}
 * 빈을 교체한다.
 *
 * <p>영속성(PR #91): 이 클래스의 로그는 콘솔(stdout)과 함께, 배포(docker·prod 프로파일)에서는 전용 롤링
 * 파일에도 남는다(logback-spring.xml의 {@code AUDIT_FILE}). 그 파일 디렉터리에 docker-compose가 볼륨을
 * 마운트하므로, 배포 시 {@code docker compose up -d --remove-orphans}로 컨테이너가 교체돼 stdout 로그가
 * 사라져도 감사 기록은 보존돼 사후 추적이 가능하다.
 *
 * <p>남기는 값은 모두 비민감 스칼라(스태프 id·예약 id·결제 id·금액)다 — 빌링키·카드번호 원본은 절대 남기지 않는다.
 * "AUDIT" 마커로 시작해 로그 파이프라인에서 감사 라인만 선별할 수 있게 한다.
 */
@Slf4j
@Component
public class Slf4jPaymentChargeAuditLogger implements PaymentChargeAuditLogger {

    @Override
    public void recordChargeAccepted(
            PaymentChargeChannel channel, Long actorMemberId, Long reservationId, Long paymentId, int amount) {
        log.info("AUDIT 진료비 청구 접수: channel={}, actorMemberId={}, reservationId={}, paymentId={}, amount={}",
                channel, actorMemberId, reservationId, paymentId, amount);
    }
}
