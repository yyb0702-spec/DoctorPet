package com.doctorpet.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.notification.dto.response.NotificationReadAllResponse;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.push.NotificationCreatedEvent;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.time.TimePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final Long HOSPITAL_ID = 20L;
    private static final Long NOTIFICATION_ID = 100L;
    private static final NotificationRecipient OWNER = NotificationRecipient.member(OWNER_ID);
    private static final NotificationRecipient HOSPITAL = NotificationRecipient.hospital(HOSPITAL_ID);
    // 고정 Clock — 읽음 시각이 공통 applicationClock을 통해 계산되는지 검증하기 위함(PR #87 P2).
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), TimePolicy.SEOUL_ZONE_ID);

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    // createIfAbsent가 멱등 저장을 프록시(REQUIRES_NEW)로 위임할 때 쓰는 자기참조. 이 클래스의 다른 테스트는
    // 사용하지 않으므로 mock만 주입한다(createIfAbsent 동시성·멱등은 Level 3 통합 테스트가 검증).
    @Mock
    private ObjectProvider<NotificationService> selfProvider;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(
                notificationRepository, FIXED_CLOCK, eventPublisher, selfProvider);
    }

    @Test
    @DisplayName("읽음 처리: 존재하지 않는 알림이면 NOTIFICATION_NOT_FOUND(404)")
    void markAsRead_notFound() {
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(OWNER, NOTIFICATION_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("읽음 처리: 타 수신자 알림이면 NOTIFICATION_ACCESS_DENIED(403)이고 조건부 UPDATE를 호출하지 않는다")
    void markAsRead_notOwner_forbidden() {
        Notification notification = memberNotification(OTHER_ID);
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markAsRead(OWNER, NOTIFICATION_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        assertThat(notification.isRead()).isFalse();
        verify(notificationRepository, never()).markReadIfUnread(any(), any(), any(), any());
    }

    @Test
    @DisplayName("격리: 회원은 병원(HOSPITAL) 알림에 접근하면 403이고 조건부 UPDATE를 호출하지 않는다")
    void markAsRead_memberCannotReadHospitalNotification() {
        Notification hospitalNotification = hospitalNotification();
        given(notificationRepository.findById(NOTIFICATION_ID))
                .willReturn(Optional.of(hospitalNotification));

        assertThatThrownBy(() -> notificationService.markAsRead(OWNER, NOTIFICATION_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        verify(notificationRepository, never()).markReadIfUnread(any(), any(), any(), any());
    }

    @Test
    @DisplayName("읽음 처리: 본인 알림이면 수신자·id·공통 Clock 기준 시각으로 조건부 UPDATE(markReadIfUnread)를 호출한다")
    void markAsRead_invokesConditionalUpdate() {
        Notification notification = memberNotification(OWNER_ID);
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));
        given(notificationRepository.markReadIfUnread(
                eq(NOTIFICATION_ID), eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any()))
                .willReturn(1);

        notificationService.markAsRead(OWNER, NOTIFICATION_ID);

        ArgumentCaptor<LocalDateTime> readAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepository).markReadIfUnread(
                eq(NOTIFICATION_ID), eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), readAt.capture());
        // 읽음 시각은 TimePolicy 직접 계산이 아니라 주입된 공통 Clock으로 만들어진다.
        assertThat(readAt.getValue()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("읽음 처리 멱등: 이미 읽은 알림이라 갱신 0건이어도 예외 없이 멱등하게 처리한다")
    void markAsRead_isIdempotentWhenNoRowUpdated() {
        Notification notification = memberNotification(OWNER_ID);
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));
        given(notificationRepository.markReadIfUnread(
                eq(NOTIFICATION_ID), eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any()))
                .willReturn(0);

        notificationService.markAsRead(OWNER, NOTIFICATION_ID);

        verify(notificationRepository).markReadIfUnread(
                eq(NOTIFICATION_ID), eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any());
    }

    @Test
    @DisplayName("미읽음 개수: 인증 수신자의 read_at NULL 건수만 세어 DTO로 반환한다")
    void getUnreadCount_countsUnreadOnly() {
        given(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                NotificationRecipientType.MEMBER, OWNER_ID)).willReturn(3L);

        assertThat(notificationService.getUnreadCount(OWNER).unreadCount()).isEqualTo(3L);
        verify(notificationRepository).countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                NotificationRecipientType.MEMBER, OWNER_ID);
    }

    @Test
    @DisplayName("미읽음 개수: 병원 수신자는 (HOSPITAL, hospitalId) 기준으로 센다")
    void getUnreadCount_hospitalRecipient() {
        given(notificationRepository.countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                NotificationRecipientType.HOSPITAL, HOSPITAL_ID)).willReturn(5L);

        assertThat(notificationService.getUnreadCount(HOSPITAL).unreadCount()).isEqualTo(5L);
        verify(notificationRepository).countByRecipientTypeAndRecipientIdAndReadAtIsNull(
                NotificationRecipientType.HOSPITAL, HOSPITAL_ID);
    }

    @Test
    @DisplayName("모두 읽음: 인증 수신자의 미읽음을 공통 Clock 시각으로 bulk 갱신하고 갱신 건수를 반환한다")
    void markAllRead_updatesWithClockAndReturnsCount() {
        given(notificationRepository.markAllReadForRecipient(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any())).willReturn(4);

        NotificationReadAllResponse response = notificationService.markAllRead(OWNER);

        assertThat(response.updatedCount()).isEqualTo(4);
        ArgumentCaptor<LocalDateTime> readAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationRepository).markAllReadForRecipient(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), readAt.capture());
        // 읽음 시각은 markAsRead와 동일하게 주입된 공통 Clock으로 만들어진다.
        assertThat(readAt.getValue()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("모두 읽음 멱등: 미읽음이 없어 갱신 0건이면 예외 없이 0을 반환한다")
    void markAllRead_isIdempotentWhenNothingUnread() {
        given(notificationRepository.markAllReadForRecipient(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any())).willReturn(0);

        assertThat(notificationService.markAllRead(OWNER).updatedCount()).isZero();
        verify(notificationRepository).markAllReadForRecipient(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any());
    }

    @Test
    @DisplayName("전체 삭제: 수신자로 하드 삭제를 위임하고 삭제 건수를 돌려준다")
    void deleteAll_delegatesAndReturnsCount() {
        given(notificationRepository.deleteAllForRecipient(
                NotificationRecipientType.MEMBER, OWNER_ID)).willReturn(3);

        assertThat(notificationService.deleteAll(OWNER).deletedCount()).isEqualTo(3);
        verify(notificationRepository).deleteAllForRecipient(
                NotificationRecipientType.MEMBER, OWNER_ID);
    }

    @Test
    @DisplayName("전체 삭제: 삭제할 게 없으면 0건으로 멱등하다")
    void deleteAll_isIdempotentWhenEmpty() {
        given(notificationRepository.deleteAllForRecipient(
                NotificationRecipientType.MEMBER, OWNER_ID)).willReturn(0);

        assertThat(notificationService.deleteAll(OWNER).deletedCount()).isZero();
    }

    @Test
    @DisplayName("전체 삭제: 병원 수신은 병원 단위로 삭제한다(read-all과 같은 수신자 모델)")
    void deleteAll_hospitalRecipient_deletesByHospital() {
        given(notificationRepository.deleteAllForRecipient(
                NotificationRecipientType.HOSPITAL, HOSPITAL_ID)).willReturn(5);

        assertThat(notificationService.deleteAll(HOSPITAL).deletedCount()).isEqualTo(5);
        verify(notificationRepository).deleteAllForRecipient(
                NotificationRecipientType.HOSPITAL, HOSPITAL_ID);
    }

    @Test
    @DisplayName("목록 조회: isRead=null이면 전체를, 최신순(createdAt desc)으로 조회한다")
    void getMyNotifications_all() {
        given(notificationRepository.findByRecipientTypeAndRecipientId(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any(Pageable.class)))
                .willReturn(Page.empty());

        notificationService.getMyNotifications(OWNER, null, 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByRecipientTypeAndRecipientId(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    @DisplayName("목록 조회: isRead=true이면 읽은 알림만(read_at NOT NULL) 조회한다")
    void getMyNotifications_readOnly() {
        given(notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNotNull(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any(Pageable.class)))
                .willReturn(Page.empty());

        notificationService.getMyNotifications(OWNER, true, 0, 20);

        verify(notificationRepository).findByRecipientTypeAndRecipientIdAndReadAtIsNotNull(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("목록 조회: isRead=false이면 미읽음 알림만(read_at NULL) 조회한다")
    void getMyNotifications_unreadOnly() {
        given(notificationRepository.findByRecipientTypeAndRecipientIdAndReadAtIsNull(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any(Pageable.class)))
                .willReturn(Page.empty());

        notificationService.getMyNotifications(OWNER, false, 0, 20);

        verify(notificationRepository).findByRecipientTypeAndRecipientIdAndReadAtIsNull(
                eq(NotificationRecipientType.MEMBER), eq(OWNER_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("생성(회원): 호환 오버로드는 (MEMBER, memberId) 수신으로 저장하고 그 수신자로 이벤트를 발행한다")
    void create_memberOverload_savesAndPublishesEvent() {
        Notification saved = memberNotification(OWNER_ID);
        given(notificationRepository.save(any(Notification.class))).willReturn(saved);

        notificationService.create(
                OWNER_ID,
                NotificationType.PAYMENT_RESULT,
                "진료비 결제가 완료되었습니다.",
                NotificationResourceType.PAYMENT,
                55L
        );

        verify(notificationRepository).save(any(Notification.class));
        ArgumentCaptor<NotificationCreatedEvent> event =
                ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().recipientType()).isEqualTo(NotificationRecipientType.MEMBER);
        assertThat(event.getValue().recipientId()).isEqualTo(OWNER_ID);
        assertThat(event.getValue().payload().type())
                .isEqualTo(NotificationType.PAYMENT_RESULT.name());
    }

    @Test
    @DisplayName("생성(병원): (HOSPITAL, hospitalId) 수신으로 저장하고 그 수신자로 이벤트를 발행한다")
    void create_hospitalRecipient_savesAndPublishesEvent() {
        Notification saved = hospitalNotification();
        given(notificationRepository.save(any(Notification.class))).willReturn(saved);

        notificationService.create(
                NotificationRecipientType.HOSPITAL,
                HOSPITAL_ID,
                NotificationType.RESERVATION_CONFIRMED,
                "새 예약 요청이 접수되었습니다.",
                NotificationResourceType.RESERVATION,
                77L
        );

        ArgumentCaptor<NotificationCreatedEvent> event =
                ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().recipientType()).isEqualTo(NotificationRecipientType.HOSPITAL);
        assertThat(event.getValue().recipientId()).isEqualTo(HOSPITAL_ID);
    }

    private Notification memberNotification(Long memberId) {
        return Notification.create(
                NotificationRecipientType.MEMBER,
                memberId,
                NotificationType.PAYMENT_RESULT,
                "진료비 결제가 완료되었습니다.",
                NotificationResourceType.PAYMENT,
                55L
        );
    }

    private Notification hospitalNotification() {
        return Notification.create(
                NotificationRecipientType.HOSPITAL,
                HOSPITAL_ID,
                NotificationType.RESERVATION_CONFIRMED,
                "새 예약 요청이 접수되었습니다.",
                NotificationResourceType.RESERVATION,
                77L
        );
    }
}
