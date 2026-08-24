package com.doctorpet.domain.notification.service;

// 알림 저장·조회·읽음 처리 서비스. 수신자 식별은 호출자(컨트롤러)에서 해석한 NotificationRecipient(회원/병원)로만 하고,
// 읽음 처리 시 수신자 소유권을 서버에서 재검증한다(다른 수신자 알림 접근 403, 없음 404 — 회원↔병원 격리).

import com.doctorpet.domain.notification.dto.response.NotificationPageResponse;
import com.doctorpet.domain.notification.dto.response.NotificationDeleteAllResponse;
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
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    // 멱등 발행 전용 dedup_key UNIQUE 제약 이름(Notification 엔티티 @Table.uniqueConstraints와 일치). 이 제약의
    // 위반만 골라 흡수하기 위한 판별 기준이다(소문자 비교로 contains 판정).
    private static final String DEDUP_KEY_CONSTRAINT = "uk_notifications_dedup_key";

    private final NotificationRepository notificationRepository;
    // JPA 감사 시각(createdAt/updatedAt)과 같은 서울 기준 Clock(applicationClock). 읽음 시각도 이 Clock으로 만들어
    // 업무 시각과 감사 시각이 같은 시계를 쓰게 한다(SA 시간 정책, PR #87 P2 리뷰 반영).
    private final Clock clock;
    // 저장 커밋 이후 실시간 전송(SSE)을 트리거한다. AFTER_COMMIT 리스너가 받아 처리하므로 롤백 시 전송되지 않는다(SA §9-8).
    private final ApplicationEventPublisher eventPublisher;
    // 멱등 저장(saveIdempotent)을 프록시 경유로 호출해 REQUIRES_NEW 트랜잭션 경계를 적용하기 위한 자기참조.
    // 같은 빈 내부 호출은 프록시를 우회해 @Transactional이 무시되므로, 순환 초기화 없는 ObjectProvider로 지연 주입한다.
    private final ObjectProvider<NotificationService> selfProvider;

    // 상태 전이 이벤트 수신자에게 알림을 저장한다. 수신자(memberId)는 이벤트 발행 도메인이 서버에서 확정해 전달한다.
    // REQUIRED로 예약 상태 변경 트랜잭션에 참여한다. create에서 발생한 unchecked 예외는
    // 호출 트랜잭션까지 전파되어 예약 상태·슬롯·이력과 알림 저장을 함께 롤백한다.
    // 기존 발행부(예약·결제)의 memberId 시그니처 호환용 오버로드. (MEMBER, memberId) 수신으로 위임한다.
    // 이 오버로드 덕분에 예약·결제 발행부 코드는 바뀌지 않는다.
    @Transactional(propagation = Propagation.REQUIRED)
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

    // 같은 (수신자·유형·리소스)의 알림을 결제당 1건으로만 남기는 멱등 발행(PR #139). 결제 고도화 3.6의 "결제 확인 중"
    // 안내에 쓴다(회원 수신). 존재 조회는 반복 사이클의 흔한 경우를 값싸게 걸러내는 빠른 경로일 뿐 원자적 보장은 아니다 —
    // 락 밖 경로(웹훅 단건 트리거)가 배치와 동시에 같은 결제를 처리하면 둘 다 "없음"을 읽을 수 있다. 최종 보장은 dedup_key
    // UNIQUE 제약(uk_notifications_dedup_key)이 하며, 동시 삽입 중 진 트랜잭션은 그 제약 위반으로 떨어지므로 "이미
    // 발행됨"으로 간주해 삼킨다. JPA/Hibernate는 유니크 위반도 DuplicateKeyException이 아니라
    // DataIntegrityViolationException으로 번역하므로, 예외 타입만으로는 NOT NULL·길이 등 다른 무결성 오류와 구분되지
    // 않는다 — 그래서 제약 이름이 uk_notifications_dedup_key인 위반만 골라 삼키고 나머지는 그대로 전파한다(PR #139 리뷰 P2).
    // saveIdempotent는 프록시(REQUIRES_NEW)로 호출해, 유니크 충돌 롤백이 이 메서드/호출자 트랜잭션을 오염시키지 않게 한다.
    // 이 메서드 자체는 트랜잭션을 열지 않는다(NOT_SUPPORTED) — 클래스 기본 readOnly 트랜잭션에 삽입이 묶여 catch가
    // UnexpectedRollbackException으로 번지는 것을 막는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void createIfAbsent(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        if (notificationRepository.existsByMemberIdAndTypeAndResourceTypeAndResourceId(
                memberId, type, resourceType, resourceId)) {
            return;
        }
        try {
            selfProvider.getObject().saveIdempotent(memberId, type, content, resourceType, resourceId);
        } catch (DataIntegrityViolationException e) {
            if (!isDedupKeyViolation(e)) {
                throw e; // 다른 무결성 오류(NOT NULL·길이·FK 등)는 무음 유실하지 않고 전파한다.
            }
            // 동시 호출이 먼저 같은 dedup_key(uk_notifications_dedup_key)를 저장함 — 결제당 1건 계약을 지키기 위해
            // 이 중복 키 위반만 이미 처리된 것으로 간주해 삼킨다.
        }
    }

    // 무결성 위반이 dedup_key UNIQUE(uk_notifications_dedup_key) 위반인지 판별한다. Hibernate ConstraintViolationException의
    // 제약 이름을 우선 보고, 못 얻으면 메시지로 보조 판별한다(드라이버마다 형식이 달라 최후 수단).
    private boolean isDedupKeyViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException hce) {
                String name = hce.getConstraintName();
                if (name != null) {
                    return name.toLowerCase(Locale.ROOT).contains(DEDUP_KEY_CONSTRAINT);
                }
            }
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(DEDUP_KEY_CONSTRAINT);
    }

    // 멱등 발행의 실제 저장. dedup_key(UNIQUE)로 동시 삽입을 원자적으로 1건으로 제한한다. 바깥과 독립적으로 커밋·롤백하도록
    // REQUIRES_NEW로 열어, 유니크 충돌 롤백이 호출자 트랜잭션을 오염시키지 않게 한다(createIfAbsent의 catch가 흡수).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveIdempotent(
            Long memberId,
            NotificationType type,
            String content,
            NotificationResourceType resourceType,
            Long resourceId
    ) {
        // createIdempotent는 recipient=(MEMBER, memberId)로 저장한다(고도화 3.10 수신자 모델). SSE 라우팅도 그
        // 수신자(회원)로 나가도록 recipient 기반 이벤트를 발행한다.
        Notification saved = notificationRepository.save(
                Notification.createIdempotent(memberId, type, content, resourceType, resourceId)
        );
        // 커밋 이후에만 실시간 전송하도록 이벤트를 등록한다(롤백 시 전송되지 않음, create와 동일).
        eventPublisher.publishEvent(
                new NotificationCreatedEvent(
                        saved.getRecipientType(), saved.getRecipientId(), NotificationResponse.from(saved))
        );
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

    // 수신자의 알림을 모두 하드 삭제한다. 없으면 0건을 반환하고 멱등하다. 병원 수신은 병원 단위 공유이므로
    // 한 스태프의 전체 삭제가 그 병원 알림 전체를 지운다(read-all과 같은 수신자 모델).
    @Transactional
    public NotificationDeleteAllResponse deleteAll(NotificationRecipient recipient) {
        int deleted = notificationRepository.deleteAllForRecipient(
                recipient.type(), recipient.id());
        return NotificationDeleteAllResponse.of(deleted);
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
