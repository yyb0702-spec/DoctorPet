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
     * 호출하지 않는다. 구현은 <b>전체 삭제 후에도 결제당 정확히 1회</b>만 저장한다. 반복 사이클은
     * delivery mark 존재 조회로 값싸게 걸러내고, 락 밖 경로(웹훅 단건 트리거)가 배치와 동시에 처리하는 경합은 분리된
     * {@code notification_delivery_marks.dedup_key} UNIQUE 제약으로 원자적으로 1건만 저장되게 막는다(동시 삽입 중 진 트랜잭션은 이미 발행된 것으로 흡수). 유형별
     * 독립 키라 정정 발행 시 같은 결제에 여러 {@code PAYMENT_RESULT}가 쌓이는 정상 동작은 막지 않는다.
     *
     * @param guardianMemberId 알림 수신자(예약 보호자)
     * @param reservationId    관련 예약
     * @param paymentId        결제 레코드
     * @param amount           진료비 금액(원)
     */
    void publishPendingNotice(Long guardianMemberId, Long reservationId, Long paymentId, int amount);
}
