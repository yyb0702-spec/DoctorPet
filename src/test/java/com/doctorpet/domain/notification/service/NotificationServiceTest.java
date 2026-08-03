package com.doctorpet.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_ID = 8L;
    private static final Long NOTIFICATION_ID = 100L;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    @DisplayName("읽음 처리: 존재하지 않는 알림이면 NOTIFICATION_NOT_FOUND(404)")
    void markAsRead_notFound() {
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(OWNER_ID, NOTIFICATION_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("읽음 처리: 타 사용자 알림이면 NOTIFICATION_ACCESS_DENIED(403)이고 조건부 UPDATE를 호출하지 않는다")
    void markAsRead_notOwner_forbidden() {
        Notification notification = paymentNotification(OTHER_ID);
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));

        assertThatThrownBy(() -> notificationService.markAsRead(OWNER_ID, NOTIFICATION_ID))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        assertThat(notification.isRead()).isFalse();
        verify(notificationRepository, never()).markReadIfUnread(any(), any(), any());
    }

    @Test
    @DisplayName("읽음 처리: 본인 알림이면 소유자·id로 조건부 UPDATE(markReadIfUnread)를 호출한다")
    void markAsRead_invokesConditionalUpdate() {
        Notification notification = paymentNotification(OWNER_ID);
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));
        given(notificationRepository.markReadIfUnread(eq(NOTIFICATION_ID), eq(OWNER_ID), any()))
                .willReturn(1);

        notificationService.markAsRead(OWNER_ID, NOTIFICATION_ID);

        verify(notificationRepository).markReadIfUnread(eq(NOTIFICATION_ID), eq(OWNER_ID), any());
    }

    @Test
    @DisplayName("읽음 처리 멱등: 이미 읽은 알림이라 갱신 0건이어도 예외 없이 멱등하게 처리한다")
    void markAsRead_isIdempotentWhenNoRowUpdated() {
        Notification notification = paymentNotification(OWNER_ID);
        given(notificationRepository.findById(NOTIFICATION_ID)).willReturn(Optional.of(notification));
        given(notificationRepository.markReadIfUnread(eq(NOTIFICATION_ID), eq(OWNER_ID), any()))
                .willReturn(0);

        notificationService.markAsRead(OWNER_ID, NOTIFICATION_ID);

        verify(notificationRepository).markReadIfUnread(eq(NOTIFICATION_ID), eq(OWNER_ID), any());
    }

    @Test
    @DisplayName("목록 조회: isRead=null이면 전체를, 최신순(createdAt desc)으로 조회한다")
    void getMyNotifications_all() {
        given(notificationRepository.findByMemberId(eq(OWNER_ID), any(Pageable.class)))
                .willReturn(Page.empty());

        notificationService.getMyNotifications(OWNER_ID, null, 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(notificationRepository).findByMemberId(eq(OWNER_ID), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("createdAt")).isNotNull();
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    @DisplayName("목록 조회: isRead=true이면 읽은 알림만(read_at NOT NULL) 조회한다")
    void getMyNotifications_readOnly() {
        given(notificationRepository.findByMemberIdAndReadAtIsNotNull(eq(OWNER_ID), any(Pageable.class)))
                .willReturn(Page.empty());

        notificationService.getMyNotifications(OWNER_ID, true, 0, 20);

        verify(notificationRepository).findByMemberIdAndReadAtIsNotNull(eq(OWNER_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("목록 조회: isRead=false이면 미읽음 알림만(read_at NULL) 조회한다")
    void getMyNotifications_unreadOnly() {
        given(notificationRepository.findByMemberIdAndReadAtIsNull(eq(OWNER_ID), any(Pageable.class)))
                .willReturn(Page.empty());

        notificationService.getMyNotifications(OWNER_ID, false, 0, 20);

        verify(notificationRepository).findByMemberIdAndReadAtIsNull(eq(OWNER_ID), any(Pageable.class));
    }

    private Notification paymentNotification(Long memberId) {
        return Notification.create(
                memberId,
                NotificationType.PAYMENT_RESULT,
                "진료비 결제가 완료되었습니다.",
                NotificationResourceType.PAYMENT,
                55L
        );
    }
}
