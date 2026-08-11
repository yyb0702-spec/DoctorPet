package com.doctorpet.domain.payment.notification;

import com.doctorpet.domain.payment.entity.PaymentStatus;

/**
 * 결제 상태 전이 알림 발행 계약(SA §9-8, 이슈 #34·#39). 결제 결과(PAID·OFFLINE_REQUIRED 등)를 보호자에게
 * 알리기 위한 이벤트를 발행한다. MVP 알림은 폴링이며 push는 인터페이스로만 추상화한다(SA §9-8, 부록 A 미확정).
 *
 * <p>#34 범위에서는 <b>발행 계약만</b> 둔다. notifications 테이블·엔티티(#39)가 아직 없어 실제 저장은 하지 않고
 * 기본 구현({@link LoggingPaymentNotificationPublisher})은 로그만 남긴다. #39가 저장 구현을 제공하면
 * 결제 흐름 코드 변경 없이 그 빈이 대체한다.
 */
public interface PaymentNotificationPublisher {

    /**
     * 진료비 청구 결과를 발행한다. 결제 흐름의 트랜잭션 커밋 이후에 호출한다(발행 실패가 결제 확정을 되돌리지 않도록).
     *
     * @param guardianMemberId 알림 수신자(예약 보호자)
     * @param reservationId    관련 예약
     * @param paymentId        결제 레코드
     * @param status           확정된 결제 상태
     * @param amount           진료비 금액(원). 알림 문구에 표기한다(빌링키·카드번호 등 민감정보는 담지 않는다).
     */
    void publishChargeResult(Long guardianMemberId, Long reservationId, Long paymentId, PaymentStatus status, int amount);

    /**
     * 결제 확인 중 안내를 발행한다(결제 고도화 3.6). 정산이 오래 미확정으로 {@code RECONCILE_STUCK}에 도달했을 때만
     * 호출해 PENDING 무음을 해소한다 — 결제 상태는 바꾸지 않고 보호자에게 안내만 추가한다. 최초/일시적 PENDING에는
     * 호출하지 않는다. 구현은 (수신자·유형·리소스) 존재조회로 <b>사실상 결제당 1회</b>만 저장한다(이미 안내한 결제는
     * 다시 저장하지 않는다) — 정산 배치는 단일 인스턴스로 직렬화돼 반복 사이클에도 중복되지 않는다. 다만 존재조회→저장이
     * 원자적이지 않아, 락 밖 경로(웹훅 단건 트리거)가 배치와 동시에 같은 결제를 처리하면 드물게 안내가 중복될 수 있다.
     * 이는 상태·금액·정산에 영향 없는 안내 알림에 한정되며(§3.6은 STRICT 동시성 대상 아님), 정정 발행 시 여러 개의
     * {@code PAYMENT_RESULT}가 같은 결제 리소스에 정상적으로 쌓이므로 DB UNIQUE로 강제하지 않는다(마이그레이션 회피).
     *
     * @param guardianMemberId 알림 수신자(예약 보호자)
     * @param reservationId    관련 예약
     * @param paymentId        결제 레코드
     * @param amount           진료비 금액(원)
     */
    void publishPendingNotice(Long guardianMemberId, Long reservationId, Long paymentId, int amount);
}
