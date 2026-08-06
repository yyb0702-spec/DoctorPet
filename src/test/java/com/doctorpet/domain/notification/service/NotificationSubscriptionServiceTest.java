package com.doctorpet.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.push.SseEmitterRegistry;
import com.doctorpet.domain.notification.repository.SseTicketRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class NotificationSubscriptionServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String TICKET = "ticket-uuid";

    @Mock
    private SseTicketRepository ticketRepository;

    @Mock
    private SseEmitterRegistry registry;

    @InjectMocks
    private NotificationSubscriptionService subscriptionService;

    @Test
    @DisplayName("유효한 티켓이면 소비해 확정한 memberId로 SSE 연결을 등록한다")
    void subscribe_validTicket_registersForResolvedMember() {
        SseEmitter emitter = new SseEmitter();
        given(ticketRepository.consume(TICKET)).willReturn(Optional.of(MEMBER_ID));
        given(registry.register(MEMBER_ID)).willReturn(emitter);

        SseEmitter result = subscriptionService.subscribe(TICKET);

        assertThat(result).isSameAs(emitter);
        verify(registry).register(MEMBER_ID);
    }

    @Test
    @DisplayName("없거나 만료·이미 사용된 티켓이면 401(SSE_TICKET_INVALID)이고 연결을 열지 않는다")
    void subscribe_invalidTicket_throwsAndDoesNotRegister() {
        given(ticketRepository.consume(TICKET)).willReturn(Optional.empty());

        assertThatThrownBy(() -> subscriptionService.subscribe(TICKET))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.SSE_TICKET_INVALID);

        verifyNoInteractions(registry);
    }

    @Test
    @DisplayName("동시 연결이 상한 이상이면 429(SSE_TOO_MANY_CONNECTIONS)이고 새 연결을 등록하지 않는다")
    void subscribe_atConnectionCap_throwsAndDoesNotRegister() {
        given(ticketRepository.consume(TICKET)).willReturn(Optional.of(MEMBER_ID));
        given(registry.connectionCount(MEMBER_ID)).willReturn(5);

        assertThatThrownBy(() -> subscriptionService.subscribe(TICKET))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.SSE_TOO_MANY_CONNECTIONS);

        verify(registry, never()).register(MEMBER_ID);
    }
}
