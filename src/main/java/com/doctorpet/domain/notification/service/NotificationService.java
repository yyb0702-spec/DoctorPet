package com.doctorpet.domain.notification.service;

// 알림 저장·조회·읽음 처리 서비스. 수신자 식별은 호출자(컨트롤러)에서 해석한 NotificationRecipient(회원/병원)로만 하고,
// 읽음 처리 시 수신자 소유권을 서버에서 재검증한다(다른 수신자 알림 접근 403, 없음 404 — 회원↔병원 격리).

import com.doctorpet.domain.notification.dto.response.NotificationPageResponse;
import com.doctorpet.domain.notification.dto.response.NotificationReadAllResponse;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.push.NotificationCreatedEvent;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    // JPA 감사 시각(createdAt/updatedAt)과 같은 서울 기준 Clock(applicationClock). 읽음 시각도 이 Clock으로 만들어
    // 업무 시각과 감사 시각이 같은 시계를 쓰게 한다(SA 시간 정책, PR #87 P2 리뷰 반영).
    private final Clock clock;
    // 저장 커밋 이후 실시간 전송(SSE)을 트리거한다. AFTER_COMMIT 리스너가 받아 처리하므로 롤백 시 전송되지 않는다(SA §9-8).
    private final ApplicationEventPublisher eventPublisher;

    // 기존 발행부(예약·결제)의 memberId 시그니처 호환용 오버로드. (MEMBER, memberId) 수신으로 위임한다 —
    // 이 오버로드 덕분에 예약·결제 발행부 코드는 바뀌지 않는다(고도화 3.10 제약).
    @Transactional
    public Notification create(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        return create(NotificationRecipientType.MEMBER, memberId, type, content, resourceType, resourceId);
    }

    // 상태 전이 이벤트 수신자(회원 또는 병원)에게 알림을 저장한다. 수신자는 발행 도메인이 서버에서 확정해 전달한다.
    @Transactional
    public Notification create(
            NotificationRecipientType recipientType,
            Long recipientId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        Notification saved = notificationRepository.save(
                Notification.create(recipientType, recipientId, type, content, resourceType, resourceId)
        );
        // 커밋 이후에만 실시간 전송하도록 이벤트를 등록한다(수신자 식별은 응답 DTO에 없으므로 이벤트에 함께 싣는다).
        eventPublisher.publishEvent(
                new NotificationCreatedEvent(
                        saved.getRecipientType(),
                        saved.getRecipientId(),
                        NotificationResponse.from(saved)
                )
        );
        return saved;
    }

    // 수신자(회원/병원)의 알림을 최신순으로 페이징 조회한다. isRead가 null이면 전체, true/false면 읽음/미읽음만 반환한다.
    public NotificationPageResponse getMyNotifications(
            NotificationRecipient recipient,
            Boolean isRead,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Notification> result;
        if (isRead == null) {
            result = notificationRepository.findByRecipientTypeAndRecipientId(
                    recipient.type(), recipient.id(), pageable);
        } else if (isRead) {
            result = notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNotNull(
                    recipient.type(), recipient.id(), pageable);
        } else {
            result = notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNull(
                    recipient.type(), recipient.id(), pageable);
        }

        return NotificationPageResponse.from(result.map(NotificationResponse::from));
    }

    // 수신자의 미읽음 알림 개수를 반환한다(배지 표시용). 목록을 폴링하지 않고 개수만 조회한다(#134).
    public NotificationUnreadCountResponse getUnreadCount(NotificationRecipient recipient) {
        return NotificationUnreadCountResponse.of(
                notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                        recipient.type(), recipient.id()));
    }

    // 수신자의 미읽음 알림을 모두 읽음 처리한다(#134). 미읽음이 없으면 0건을 반환하고 예외 없이 멱등하다.
    // 병원 수신은 병원 단위 공유이므로 한 스태프의 모두읽음이 그 병원 알림 전체에 반영된다.
    @Transactional
    public NotificationReadAllResponse markAllRead(NotificationRecipient recipient) {
        int updated = notificationRepository.markAllReadForRecipient(
                recipient.type(), recipient.id(), LocalDateTime.now(clock));
        return NotificationReadAllResponse.of(updated);
    }

    // 개별 알림을 읽음 처리한다. 존재하지 않으면 404, 수신자의 알림이 아니면 403. 이미 읽은 알림이면 read_at 유지(멱등 200).
    // 병원 단위 공유에서는 서로 다른 스태프가 같은 알림을 동시에 읽는 것이 정상이라 조건부 UPDATE로 멱등을 보장한다.
    @Transactional
    public void markAsRead(NotificationRecipient recipient, Long notificationId) {
        // 존재·소유 수신자를 먼저 확정해 명확한 404/403을 준다(조건부 UPDATE만으로는 둘을 구분할 수 없다).
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ServiceException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.isReceivedBy(recipient.type(), recipient.id())) {
            throw new ServiceException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        }

        // WHERE read_at IS NULL 조건부 UPDATE로 최초 1회만 기록한다 — 동시 요청에도 최초 시각이 보존된다(PR #87 P2).
        // 갱신 0건은 이미 읽은 알림이므로 예외 없이 멱등 200으로 둔다.
        notificationRepository.markReadIfUnread(
                notificationId, recipient.type(), recipient.id(), LocalDateTime.now(clock));
    }
}
