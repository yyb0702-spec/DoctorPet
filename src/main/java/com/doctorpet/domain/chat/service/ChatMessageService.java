package com.doctorpet.domain.chat.service;

import com.doctorpet.domain.chat.dto.request.ChatMessageSendRequest;
import com.doctorpet.domain.chat.dto.response.ChatMessagePageResponse;
import com.doctorpet.domain.chat.dto.response.ChatMessageResponse;
import com.doctorpet.domain.chat.entity.ChatMessage;
import com.doctorpet.domain.chat.entity.ChatSenderType;
import com.doctorpet.domain.chat.event.ChatMessageCreatedEvent;
import com.doctorpet.domain.chat.exception.ChatErrorCode;
import com.doctorpet.domain.chat.port.ChatStaffHospitalPort;
import com.doctorpet.domain.chat.port.ChatMemberProfilePort;
import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Pattern UUID_CLIENT_MESSAGE_ID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final EnumSet<ReservationStatus> WRITABLE_STATUSES = EnumSet.of(
            ReservationStatus.REQUESTED,
            ReservationStatus.CONFIRMED,
            ReservationStatus.NO_SHOW_PENDING,
            ReservationStatus.CHECKED_IN,
            ReservationStatus.IN_TREATMENT
    );

    private final ReservationService reservationService;
    private final ChatStaffHospitalPort staffHospitalPort;
    private final ChatMemberProfilePort memberProfilePort;
    private final HospitalService hospitalService;
    private final ChatMessageRepository chatMessageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock applicationClock;

    /** HTTP 조회와 STOMP SUBSCRIBE/SEND가 공통으로 사용하는 예약 관계 인가다. */
    @Transactional(readOnly = true)
    public void assertAccessible(Long reservationId, MemberPrincipal principal) {
        authorize(reservationService.findReservationForChat(reservationId), principal);
    }

    @Transactional
    public ChatMessageResponse send(
            Long reservationId,
            MemberPrincipal principal,
            ChatMessageSendRequest request
    ) {
        if (request == null || !StringUtils.hasText(request.content())
                || request.content().length() > 1000
                || !isValidClientMessageId(request.clientMessageId())) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
        // 종료 상태 조건부 UPDATE와 이 행 잠금을 공유해, 종료가 먼저 확정된 뒤에는 저장되지 않는다.
        Reservation reservation = reservationService.findReservationForChatForUpdate(reservationId);
        ChatParticipant participant = authorize(reservation, principal);
        if (!WRITABLE_STATUSES.contains(reservation.getStatus())) {
            throw new ServiceException(ChatErrorCode.MESSAGE_SEND_NOT_ALLOWED);
        }

        ChatMessage existing = chatMessageRepository
                .findByReservationIdAndMemberIdAndClientMessageId(
                        reservationId, principal.memberId(), request.clientMessageId())
                .orElse(null);
        if (existing != null) {
            return toResponse(existing, hospitalName(reservation.getHospitalId()));
        }

        ChatMessage saved = chatMessageRepository.saveAndFlush(ChatMessage.create(
                reservationId,
                participant.senderType(),
                // 모든 스레드는 하나의 예약 병원에 귀속된다. 보호자 발신도 이를 저장해
                // chat_messages.hospital_id의 감사·격리 키가 NULL이 되지 않게 한다.
                reservation.getHospitalId(),
                principal.memberId(),
                request.content(), request.clientMessageId()
        ));
        ChatMessageResponse response = toResponse(saved, hospitalName(reservation.getHospitalId()));
        eventPublisher.publishEvent(new ChatMessageCreatedEvent(reservationId, response));
        return response;
    }

    private boolean isValidClientMessageId(String clientMessageId) {
        if (!StringUtils.hasText(clientMessageId) || clientMessageId.length() != 36) {
            return false;
        }
        return UUID_CLIENT_MESSAGE_ID.matcher(clientMessageId).matches();
    }

    @Transactional(readOnly = true)
    public ChatMessagePageResponse getMessages(
            Long reservationId,
            MemberPrincipal principal,
            Long afterMessageId,
            int size
    ) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ServiceException(ChatErrorCode.INVALID_AFTER_MESSAGE);
        }
        Reservation reservation = reservationService.findReservationForChat(reservationId);
        authorize(reservation, principal);
        ChatMessage cursor = null;
        if (afterMessageId != null) {
            cursor = chatMessageRepository.findById(afterMessageId)
                    .orElseThrow(() -> new ServiceException(ChatErrorCode.INVALID_AFTER_MESSAGE));
            if (!cursor.getReservationId().equals(reservationId)) {
                throw new ServiceException(ChatErrorCode.INVALID_AFTER_MESSAGE);
            }
        }

        PageRequest page = PageRequest.of(0, size + 1);
        List<ChatMessage> loaded = afterMessageId == null
                ? chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(reservationId, page)
                : chatMessageRepository.findAfterCursor(
                        reservationId, cursor.getCreatedAt(), afterMessageId, page);
        boolean hasNext = loaded.size() > size;
        List<ChatMessage> messages = hasNext ? loaded.subList(0, size) : loaded;
        String hospitalName = hospitalName(reservation.getHospitalId());
        Map<Long, String> guardianNicknames = messages.stream()
                .filter(message -> message.getSenderType() == ChatSenderType.GUARDIAN)
                .map(ChatMessage::getMemberId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), memberProfilePort::getGuardianNickname));
        List<ChatMessageResponse> responses = messages.stream()
                .map(message -> toResponse(message, hospitalName, guardianNicknames))
                .toList();
        // 다음 페이지가 있을 때만 "next" 커서를 제공한다. 마지막/빈 페이지에서 이전 커서를
        // 되돌려 주면 nextAfterMessageId가 다음 페이지를 가리킨다는 응답 의미가 모호해진다.
        Long nextAfterMessageId = hasNext
                ? responses.get(responses.size() - 1).messageId()
                : null;
        return new ChatMessagePageResponse(responses, nextAfterMessageId, hasNext);
    }

    @Transactional
    public void markRead(Long reservationId, MemberPrincipal principal, Long throughMessageId) {
        Reservation reservation = reservationService.findReservationForChat(reservationId);
        ChatParticipant participant = authorize(reservation, principal);
        ChatSenderType receivedSenderType = participant.senderType() == ChatSenderType.GUARDIAN
                ? ChatSenderType.HOSPITAL
                : ChatSenderType.GUARDIAN;
        // 클라이언트가 실제로 병합한 마지막 메시지까지만 읽음 처리한다. 상한이 없으면 최종 복구 직후
        // 저장됐지만 아직 화면에 도착하지 않은 상대 메시지까지 읽음이 된다(PR #159 리뷰 P1).
        chatMessageRepository.markReadByReservationIdAndSenderType(
                reservationId, receivedSenderType, throughMessageId,
                LocalDateTime.now(applicationClock));
    }

    @Transactional
    public long deleteExpiredMessages() {
        return chatMessageRepository.deleteExpiredMessages(
                LocalDateTime.now(applicationClock).minusYears(1));
    }

    private ChatParticipant authorize(Reservation reservation, MemberPrincipal principal) {
        if (principal == null || principal.memberId() == null || principal.role() == null) {
            throw new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED);
        }
        if (MemberRole.GUARDIAN.name().equals(principal.role())) {
            if (!reservation.isOwnedBy(principal.memberId())) {
                throw new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED);
            }
            return new ChatParticipant(ChatSenderType.GUARDIAN, null);
        }
        if (MemberRole.HOSPITAL_STAFF.name().equals(principal.role())) {
            Long hospitalId = staffHospitalPort.findHospitalIdByMemberId(principal.memberId())
                    .orElseThrow(() -> new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED));
            if (!reservation.getHospitalId().equals(hospitalId)) {
                throw new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED);
            }
            return new ChatParticipant(ChatSenderType.HOSPITAL, hospitalId);
        }
        throw new ServiceException(ChatErrorCode.CHAT_ACCESS_DENIED);
    }

    private String hospitalName(Long hospitalId) {
        return hospitalService.getHospitalDetail(hospitalId).name();
    }

    private ChatMessageResponse toResponse(ChatMessage message, String hospitalName) {
        String guardianNickname = message.getSenderType() == ChatSenderType.GUARDIAN
                ? memberProfilePort.getGuardianNickname(message.getMemberId())
                : null;
        return ChatMessageResponse.from(message, hospitalName, guardianNickname);
    }

    private ChatMessageResponse toResponse(
            ChatMessage message,
            String hospitalName,
            Map<Long, String> guardianNicknames
    ) {
        String guardianNickname = message.getSenderType() == ChatSenderType.GUARDIAN
                ? guardianNicknames.get(message.getMemberId())
                : null;
        return ChatMessageResponse.from(message, hospitalName, guardianNickname);
    }
}
