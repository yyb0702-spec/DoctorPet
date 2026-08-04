package com.doctorpet.domain.payment.audit;

/**
 * 진료비 청구 감사 기록(이슈 #84, PR-77-followups §2-4). 누가·얼마·어느 예약을 청구했는지 남겨,
 * 정상/탈취 스태프 계정의 과다청구를 사후 추적할 수 있게 한다(청구 자체를 막지는 않는다 — 후불 최종액 입력은
 * PRD/SA 설계 의도라 MVP 수용 범위). 이상 금액 임계 알림·진료 항목 기반 상한은 정책 결정이 필요해 별도로 남긴다.
 *
 * <p>{@link com.doctorpet.domain.payment.notification.PaymentNotificationPublisher}·{@code RetryBackoff}와 같이
 * 인터페이스로 분리해, 지금은 로그 기반 구현을 쓰고 향후 영속 감사 저장소로 교체할 수 있게 한다. 구현체는
 * 빌링키·카드번호 원본 등 민감정보를 남기지 않는다(AGENTS 보안 규칙).
 */
public interface PaymentChargeAuditLogger {

    /**
     * 청구 접수(Tx1 선기록 커밋) 시점의 감사 기록. 외부 승인 결과와 무관하게 "누가·얼마·어느 예약"을 남긴다.
     *
     * @param staffMemberId 청구를 실행한 스태프(인증 주체). 요청 값이 아니라 @AuthenticationPrincipal로 식별된 값이어야 한다.
     * @param reservationId 청구 대상 예약
     * @param paymentId     선기록된 결제 레코드 식별자(후속 상태·정산과 대사용)
     * @param amount        청구 금액(원)
     */
    void recordChargeAccepted(Long staffMemberId, Long reservationId, Long paymentId, int amount);
}
