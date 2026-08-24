package com.doctorpet.domain.notification.dto.response;

// SSE 구독 티켓 발급 응답. 클라이언트는 이 ticket을 GET /api/notifications/subscribe?ticket= 로 제시해 구독한다.
public record SubscribeTicketResponse(
        String ticket
) {
}
