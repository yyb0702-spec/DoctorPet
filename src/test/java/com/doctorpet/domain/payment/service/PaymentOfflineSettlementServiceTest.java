package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.PaymentChannel;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.notification.PaymentNotificationPublisher;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Level 1 — 오프라인 정산 오케스트레이션 단위 검증(#36). 실제 정산(freshlySettled)일 때만 커밋 이후 알림을 발행하고,
 * 이미 정산된 멱등 응답에는 발행하지 않는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOfflineSettlementServiceTest {

    private static final Long PAYMENT_ID = 1L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long STAFF_MEMBER_ID = 9L;
    private static final Long GUARDIAN_ID = 5L;

    @Mock private PaymentOfflineSettleTxService paymentOfflineSettleTxService;
    @Mock private PaymentNotificationPublisher notificationPublisher;

    @InjectMocks
    private PaymentOfflineSettlementService paymentOfflineSettlementService;

    private PaymentHistoryResponse settledResponse() {
        return new PaymentHistoryResponse(PAYMENT_ID, RESERVATION_ID, PaymentStatus.OFFLINE_PAID,
                PaymentChannel.OFFLINE, 50_000, "VISA", "1234",
                LocalDateTime.now(), null, LocalDateTime.now(), LocalDateTime.now(), null);
    }

    @Test
    @DisplayName("실제 정산을 수행하면 보호자 알림을 발행한다")
    void freshlySettled_publishesNotification() {
        given(paymentOfflineSettleTxService.settle(PAYMENT_ID, STAFF_MEMBER_ID))
                .willReturn(OfflineSettleOutcome.freshlySettled(settledResponse(), GUARDIAN_ID));

        PaymentHistoryResponse response =
                paymentOfflineSettlementService.settle(PAYMENT_ID, STAFF_MEMBER_ID);

        assertThat(response.status()).isEqualTo(PaymentStatus.OFFLINE_PAID);
        verify(notificationPublisher)
                .publishChargeResult(GUARDIAN_ID, RESERVATION_ID, PAYMENT_ID, PaymentStatus.OFFLINE_PAID);
    }

    @Test
    @DisplayName("이미 정산된 멱등 응답에는 알림을 발행하지 않는다")
    void alreadySettled_noNotification() {
        given(paymentOfflineSettleTxService.settle(PAYMENT_ID, STAFF_MEMBER_ID))
                .willReturn(OfflineSettleOutcome.alreadySettled(settledResponse(), GUARDIAN_ID));

        paymentOfflineSettlementService.settle(PAYMENT_ID, STAFF_MEMBER_ID);

        verify(notificationPublisher, never())
                .publishChargeResult(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
