package com.doctorpet.domain.chat.dto.response;

/** topic 구독자가 실제로 제어 프레임을 수신한 뒤 누락 이력을 최종 복구하게 하는 신호다. */
public record ChatSubscriptionReady(String type, Long reservationId) {
    public static ChatSubscriptionReady forReservation(Long reservationId) {
        return new ChatSubscriptionReady("SUBSCRIPTION_READY", reservationId);
    }
}
