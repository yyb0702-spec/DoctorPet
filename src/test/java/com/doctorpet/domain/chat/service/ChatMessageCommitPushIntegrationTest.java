package com.doctorpet.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.chat.dto.request.ChatMessageSendRequest;
import com.doctorpet.domain.chat.dto.response.ChatMessageResponse;
import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.chat.port.ChatMemberProfilePort;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Level 3 — 채팅 전달은 저장 트랜잭션의 실제 커밋 뒤에만 실행되고 전달 장애와 독립적이다. */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "jwt.secret=doctorpet-chat-integration-test-secret-key-32-bytes-minimum",
        "jwt.access-token-expiration=3600000",
        "jwt.refresh-token-expiration=1209600000"
})
class ChatMessageCommitPushIntegrationTest {

    @Autowired private ChatMessageService chatMessageService;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSlotRepository reservationSlotRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private HospitalService hospitalService;
    @MockitoBean private ChatMemberProfilePort memberProfilePort;
    @MockitoSpyBean private SimpMessagingTemplate messagingTemplate;

    private Long reservationId;
    private Long slotId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            chatMessageRepository.deleteAll(chatMessageRepository
                    .findByReservationIdOrderByCreatedAtAscIdAsc(
                            reservationId, org.springframework.data.domain.Pageable.unpaged()));
            reservationRepository.deleteById(reservationId);
        }
        if (slotId != null) {
            reservationSlotRepository.deleteById(slotId);
        }
    }

    @Test
    @DisplayName("저장 트랜잭션 커밋 뒤에만 topic 전달이 1회 실행된다")
    void commit_deliversMessageAfterward() {
        ChatFixture fixture = saveReservation();

        Long messageId = transactionTemplate().execute(status -> chatMessageService.send(
                fixture.reservationId(), fixture.guardian(), new ChatMessageSendRequest("커밋 메시지"))
                .messageId());

        verify(messagingTemplate, times(1)).convertAndSend(
                eq(destination(fixture.reservationId())), any(ChatMessageResponse.class));
        assertThat(chatMessageRepository.existsById(messageId)).isTrue();
    }

    @Test
    @DisplayName("저장 트랜잭션이 롤백되면 topic 전달도 메시지 행도 남지 않는다")
    void rollback_doesNotDeliverOrPersistMessage() {
        ChatFixture fixture = saveReservation();

        Long messageId = transactionTemplate().execute(status -> {
            Long id = chatMessageService.send(
                    fixture.reservationId(), fixture.guardian(), new ChatMessageSendRequest("롤백 메시지"))
                    .messageId();
            status.setRollbackOnly();
            return id;
        });

        verify(messagingTemplate, never()).convertAndSend(
                eq(destination(fixture.reservationId())), any(ChatMessageResponse.class));
        assertThat(chatMessageRepository.existsById(messageId)).isFalse();
    }

    @Test
    @DisplayName("STOMP 전달 실패는 이미 커밋된 채팅 저장을 롤백하지 않는다")
    void deliveryFailure_doesNotRollbackPersistedMessage() {
        ChatFixture fixture = saveReservation();
        doThrow(new IllegalStateException("STOMP 장애 시뮬레이션"))
                .when(messagingTemplate)
                .convertAndSend(eq(destination(fixture.reservationId())), any(ChatMessageResponse.class));

        Long messageId = transactionTemplate().execute(status -> chatMessageService.send(
                fixture.reservationId(), fixture.guardian(), new ChatMessageSendRequest("전달 실패 메시지"))
                .messageId());

        verify(messagingTemplate, times(1)).convertAndSend(
                eq(destination(fixture.reservationId())), any(ChatMessageResponse.class));
        assertThat(chatMessageRepository.existsById(messageId)).isTrue();
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private ChatFixture saveReservation() {
        long hospitalId = System.nanoTime();
        long guardianId = hospitalId + 1;
        LocalDateTime now = LocalDateTime.now();
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                hospitalId, now.plusDays(2), now.plusDays(2).plusMinutes(30)));
        slotId = slot.getId();
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.request(
                guardianId, 1L, hospitalId, slot.getId(), 1L,
                "초코", "DOG", now, slot.getStartAt()));
        reservationId = reservation.getId();
        org.mockito.BDDMockito.given(hospitalService.getHospitalDetail(hospitalId))
                .willReturn(new HospitalDetailResponse(hospitalId, "테스트동물병원", null, null, null,
                        null, null, null, null, null, null, null, null, null, null, 0L, false));
        org.mockito.BDDMockito.given(memberProfilePort.getGuardianNickname(guardianId))
                .willReturn("테스트보호자");
        return new ChatFixture(reservation.getId(), new MemberPrincipal(
                guardianId, "guardian@example.com", MemberRole.GUARDIAN.name()));
    }

    private String destination(Long id) {
        return "/topic/chat/reservations/" + id;
    }

    private record ChatFixture(Long reservationId, MemberPrincipal guardian) {
    }
}
