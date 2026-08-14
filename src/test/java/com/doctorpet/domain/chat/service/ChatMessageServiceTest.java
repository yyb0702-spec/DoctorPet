package com.doctorpet.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.chat.dto.request.ChatMessageSendRequest;
import com.doctorpet.domain.chat.dto.response.ChatMessagePageResponse;
import com.doctorpet.domain.chat.entity.ChatMessage;
import com.doctorpet.domain.chat.entity.ChatSenderType;
import com.doctorpet.domain.chat.exception.ChatErrorCode;
import com.doctorpet.domain.chat.port.ChatStaffHospitalPort;
import com.doctorpet.domain.chat.port.ChatMemberProfilePort;
import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceTest {

    private static final long RESERVATION_ID = 101L;
    private static final long HOSPITAL_ID = 201L;
    private static final MemberPrincipal GUARDIAN = new MemberPrincipal(
            301L, "guardian@example.com", MemberRole.GUARDIAN.name());
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock private ReservationService reservationService;
    @Mock private ChatStaffHospitalPort staffHospitalPort;
    @Mock private ChatMemberProfilePort memberProfilePort;
    @Mock private HospitalService hospitalService;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ChatMessageService chatMessageService;

    @BeforeEach
    void setUp() {
        chatMessageService = new ChatMessageService(
                reservationService,
                staffHospitalPort,
                memberProfilePort,
                hospitalService,
                chatMessageRepository,
                eventPublisher,
                CLOCK
        );
    }

    @Test
    @DisplayName("빈 문자열·공백 문자열·1001자 메시지는 저장 전에 거부한다")
    void rejectsInvalidMessageBodies() {
        for (String content : List.of("", "   ", "가".repeat(1001))) {
            assertThatThrownBy(() -> chatMessageService.send(
                    RESERVATION_ID, GUARDIAN, new ChatMessageSendRequest(content)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getErrorCode())
                    .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    @DisplayName("clientMessageId가 없거나 UUID 형식이 아니면 저장 전에 거절한다")
    void rejectsMissingOrMalformedClientMessageId() {
        for (String clientMessageId : List.of("", "not-a-uuid", "00000000-0000-0111-8111-111111111111")) {
            assertThatThrownBy(() -> chatMessageService.send(
                    RESERVATION_ID, GUARDIAN,
                    new ChatMessageSendRequest("메시지", clientMessageId)))
                    .isInstanceOf(ServiceException.class)
                    .extracting(error -> ((ServiceException) error).getErrorCode())
                    .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    @DisplayName("1000자 보호자 메시지는 예약 병원 ID를 감사 정보로 저장하고 병원명만 표시한다")
    void sendsBoundaryMessageWithReservationHospitalId() {
        Reservation reservation = requestedReservation();
        given(reservationService.findReservationForChatForUpdate(RESERVATION_ID))
                .willReturn(reservation);
        given(memberProfilePort.getGuardianNickname(GUARDIAN.memberId())).willReturn("보호자");
        given(hospitalService.getHospitalDetail(HOSPITAL_ID)).willReturn(hospitalDetail());
        given(chatMessageRepository.saveAndFlush(any(ChatMessage.class))).willAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", 401L);
            ReflectionTestUtils.setField(message, "createdAt", LocalDateTime.now(CLOCK));
            return message;
        });

        var response = chatMessageService.send(
                RESERVATION_ID, GUARDIAN, new ChatMessageSendRequest("가".repeat(1000)));

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository).saveAndFlush(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(messageCaptor.getValue().getMemberId()).isEqualTo(GUARDIAN.memberId());
        assertThat(response.senderName()).isEqualTo("보호자");
    }

    @Test
    @DisplayName("다른 예약의 커서는 거부하고 마지막 페이지의 nextAfterMessageId는 null이다")
    void rejectsForeignCursorAndDoesNotReturnNextCursorOnLastPage() {
        Reservation reservation = requestedReservation();
        given(reservationService.findReservationForChat(RESERVATION_ID)).willReturn(reservation);
        ChatMessage foreignCursor = message(999L, 999L, ChatSenderType.GUARDIAN);
        given(chatMessageRepository.findById(999L)).willReturn(Optional.of(foreignCursor));

        assertThatThrownBy(() -> chatMessageService.getMessages(
                RESERVATION_ID, GUARDIAN, 999L, 10))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_AFTER_MESSAGE);

        ChatMessage onlyMessage = message(1L, RESERVATION_ID, ChatSenderType.HOSPITAL);
        given(chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(
                eq(RESERVATION_ID), any())).willReturn(List.of(onlyMessage));
        given(hospitalService.getHospitalDetail(HOSPITAL_ID)).willReturn(hospitalDetail());

        ChatMessagePageResponse page = chatMessageService.getMessages(
                RESERVATION_ID, GUARDIAN, null, 10);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextAfterMessageId()).isNull();
        assertThat(page.messages()).singleElement().satisfies(message -> {
            assertThat(message.senderName()).isEqualTo("우리동물병원");
        });
    }

    @Test
    @DisplayName("보호자 메시지는 서버가 해석한 보호자 nickname만 표시한다")
    void returnsServerResolvedGuardianNickname() {
        Reservation reservation = requestedReservation();
        ChatMessage guardianMessage = message(1L, RESERVATION_ID, ChatSenderType.GUARDIAN);
        given(reservationService.findReservationForChat(RESERVATION_ID)).willReturn(reservation);
        given(chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(
                eq(RESERVATION_ID), any())).willReturn(List.of(guardianMessage));
        given(hospitalService.getHospitalDetail(HOSPITAL_ID)).willReturn(hospitalDetail());
        given(memberProfilePort.getGuardianNickname(guardianMessage.getMemberId()))
                .willReturn("서버보호자명");

        ChatMessagePageResponse page = chatMessageService.getMessages(
                RESERVATION_ID, GUARDIAN, null, 10);

        assertThat(page.messages()).singleElement().satisfies(message -> {
            assertThat(message.senderType()).isEqualTo(ChatSenderType.GUARDIAN);
            assertThat(message.senderName()).isEqualTo("서버보호자명");
        });
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {
            "REJECTED", "CANCELED", "HOSPITAL_CANCELED", "TREATMENT_COMPLETED", "NO_SHOW"
    })
    @DisplayName("읽기 전용 예약은 행 잠금 후 재확인해 HTTP·STOMP 공통 전송을 저장하지 않는다")
    void rejectsReadOnlyReservationAfterLock(ReservationStatus readOnlyStatus) {
        Reservation reservation = requestedReservation();
        ReflectionTestUtils.setField(reservation, "status", readOnlyStatus);
        given(reservationService.findReservationForChatForUpdate(RESERVATION_ID))
                .willReturn(reservation);

        assertThatThrownBy(() -> chatMessageService.send(
                RESERVATION_ID, GUARDIAN, new ChatMessageSendRequest("종료 후 메시지")))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ChatErrorCode.MESSAGE_SEND_NOT_ALLOWED);
        verify(chatMessageRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("병원 스태프가 읽으면 보호자 발신 메시지를 병원 단위로 읽음 처리한다")
    void hospitalReadMarksGuardianMessagesForWholeHospital() {
        MemberPrincipal staff = new MemberPrincipal(401L, "staff@example.com",
                MemberRole.HOSPITAL_STAFF.name());
        given(reservationService.findReservationForChat(RESERVATION_ID)).willReturn(requestedReservation());
        given(staffHospitalPort.findHospitalIdByMemberId(staff.memberId())).willReturn(Optional.of(HOSPITAL_ID));

        chatMessageService.markRead(RESERVATION_ID, staff);

        verify(chatMessageRepository).markReadByReservationIdAndSenderType(
                eq(RESERVATION_ID), eq(ChatSenderType.GUARDIAN), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("정확히 1년 지난 메시지도 공통 Clock 기준으로 삭제 대상에 포함한다")
    void deletesMessagesAtExactOneYearBoundary() {
        chatMessageService.deleteExpiredMessages();

        verify(chatMessageRepository).deleteExpiredMessages(
                LocalDateTime.of(2025, 8, 12, 9, 0));
    }

    private Reservation requestedReservation() {
        Reservation reservation = Reservation.request(
                GUARDIAN.memberId(), 1L, HOSPITAL_ID, 1L, 1L,
                "초코", "DOG", LocalDateTime.now(CLOCK));
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        return reservation;
    }

    private ChatMessage message(long id, long reservationId, ChatSenderType senderType) {
        ChatMessage message = ChatMessage.create(
                reservationId, senderType, HOSPITAL_ID, 777L, "안녕하세요");
        ReflectionTestUtils.setField(message, "id", id);
        ReflectionTestUtils.setField(message, "createdAt", LocalDateTime.now(CLOCK));
        return message;
    }

    private HospitalDetailResponse hospitalDetail() {
        return new HospitalDetailResponse(
                HOSPITAL_ID, "우리동물병원", null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, 0L, false);
    }
}
