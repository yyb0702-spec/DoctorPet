package com.doctorpet.domain.notification.adapter;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoringReservationNotificationPublisherTest {

    @Mock
    private NotificationService notificationService;

    @Test
    void publishWaitlistOffered_storesWaitlistEntryAndExpiration() {
        StoringReservationNotificationPublisher publisher =
                new StoringReservationNotificationPublisher(notificationService);
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 13, 10, 10);

        publisher.publishWaitlistOffered(1L, 2L, expiresAt);

        verify(notificationService).create(
                eq(1L),
                eq(NotificationType.RESERVATION_WAITLIST_OFFERED),
                contains(expiresAt.toString()),
                eq(NotificationResourceType.RESERVATION_WAITLIST),
                eq(2L)
        );
    }
}
