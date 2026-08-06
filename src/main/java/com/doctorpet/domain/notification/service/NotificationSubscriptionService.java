package com.doctorpet.domain.notification.service;

// SSE 구독 티켓 발급·소비와 연결 등록을 담당한다(SA §9-8). 수신자 식별은 발급 시 인증 principal의 memberId로만 한다.

import com.doctorpet.domain.notification.dto.response.SubscribeTicketResponse;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.push.SseEmitterRegistry;
import com.doctorpet.domain.notification.repository.SseTicketRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class NotificationSubscriptionService {

    // 회원당 동시 SSE 연결 상한(다중 탭·기기 여유를 두되 무제한 개설로 인한 리소스 소진은 막는다).
    // 죽은 연결은 heartbeat 주기(15초)에 정리되므로 상한 초과 거절은 자가 회복된다(soft cap).
    private static final int MAX_CONNECTIONS_PER_MEMBER = 5;

    private final SseTicketRepository ticketRepository;
    private final SseEmitterRegistry registry;

    // 인증된 회원에게 1회성 구독 티켓을 발급한다.
    public SubscribeTicketResponse issueTicket(Long memberId) {
        return new SubscribeTicketResponse(ticketRepository.issue(memberId));
    }

    // 티켓을 검증·소비해 수신자를 확정하고 SSE 연결을 연다. 티켓이 유효하지 않으면 401, 연결 상한 초과면 429.
    public SseEmitter subscribe(String ticket) {
        Long memberId = ticketRepository.consume(ticket)
                .orElseThrow(() -> new ServiceException(NotificationErrorCode.SSE_TICKET_INVALID));
        if (registry.connectionCount(memberId) >= MAX_CONNECTIONS_PER_MEMBER) {
            throw new ServiceException(NotificationErrorCode.SSE_TOO_MANY_CONNECTIONS);
        }
        return registry.register(memberId);
    }
}
