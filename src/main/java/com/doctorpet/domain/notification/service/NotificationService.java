package com.doctorpet.domain.notification.service;

// 알림 저장·조회·읽음 처리 서비스. 수신자 식별은 호출자(컨트롤러의 @AuthenticationPrincipal)에서 전달된
// memberId로만 하고, 읽음 처리 시 소유권을 서버에서 재검증한다(본인 아님 403, 없음 404).

import com.doctorpet.domain.notification.dto.response.NotificationPageResponse;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.time.TimePolicy;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
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

    // 상태 전이 이벤트 수신자에게 알림을 저장한다. 수신자(memberId)는 이벤트 발행 도메인이 서버에서 확정해 전달한다.
    @Transactional
    public Notification create(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        return notificationRepository.save(
                Notification.create(memberId, type, content, resourceType, resourceId)
        );
    }

    // 본인 알림을 최신순으로 페이징 조회한다. isRead가 null이면 전체, true/false면 읽음/미읽음만 반환한다.
    public NotificationPageResponse getMyNotifications(
            Long memberId,
            Boolean isRead,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Notification> result;
        if (isRead == null) {
            result = notificationRepository.findByMemberId(memberId, pageable);
        } else if (isRead) {
            result = notificationRepository.findByMemberIdAndReadAtIsNotNull(memberId, pageable);
        } else {
            result = notificationRepository.findByMemberIdAndReadAtIsNull(memberId, pageable);
        }

        return NotificationPageResponse.from(result.map(NotificationResponse::from));
    }

    // 개별 알림을 읽음 처리한다. 존재하지 않으면 404, 본인 알림이 아니면 403. 이미 읽은 알림이면 read_at 유지(멱등).
    @Transactional
    public void markAsRead(Long memberId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ServiceException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.isOwnedBy(memberId)) {
            throw new ServiceException(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        }

        notification.markRead(LocalDateTime.now(TimePolicy.SEOUL_ZONE_ID));
    }
}
