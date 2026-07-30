package com.doctorpet.domain.reservation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationProgressStatusTest {

    @Test
    @DisplayName("진료 완료 후 온라인 결제와 오프라인 결제는 모두 결제 완료다")
    void paidStatuses_arePaymentCompleted() {
        assertThat(ReservationProgressStatus.from(
                ReservationStatus.TREATMENT_COMPLETED,
                "PAID"
        )).isEqualTo(ReservationProgressStatus.PAYMENT_COMPLETED);

        assertThat(ReservationProgressStatus.from(
                ReservationStatus.TREATMENT_COMPLETED,
                "OFFLINE_PAID"
        )).isEqualTo(ReservationProgressStatus.PAYMENT_COMPLETED);
    }

    @Test
    @DisplayName("진료 완료 전 오프라인 결제 상태는 결제 완료로 바꾸지 않는다")
    void offlinePaidBeforeTreatmentCompleted_keepsReservationStatus() {
        assertThat(ReservationProgressStatus.from(
                ReservationStatus.CONFIRMED,
                "OFFLINE_PAID"
        )).isEqualTo(ReservationProgressStatus.RESERVATION_CONFIRMED);
    }
}
