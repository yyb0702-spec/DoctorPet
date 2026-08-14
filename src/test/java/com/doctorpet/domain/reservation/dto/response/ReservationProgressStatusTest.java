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

    @Test
    @DisplayName("노쇼 추가 유예 상태를 진행 상태 응답에 그대로 노출한다")
    void noShowPending_isExposed() {
        assertThat(ReservationProgressStatus.from(
                ReservationStatus.NO_SHOW_PENDING,
                null
        )).isEqualTo(ReservationProgressStatus.NO_SHOW_PENDING);
    }

    @Test
    @DisplayName("병원 취소 예약은 보호자 진행 상태에서 취소로 표시한다")
    void hospitalCancelled_isExposedAsCanceled() {
        assertThat(ReservationProgressStatus.from(
                ReservationStatus.HOSPITAL_CANCELED,
                null
        )).isEqualTo(ReservationProgressStatus.RESERVATION_CANCELED);
    }

    @Test
    @DisplayName("환불된 결제는 결제 완료가 아니라 진료 완료로 되돌아간다(#37)")
    void refunded_isNotPaymentCompleted() {
        // 환불(REFUNDED)은 PAID·OFFLINE_PAID 어디에도 해당하지 않으므로 예약 상태(진료완료)가 그대로 노출된다.
        // 결제완료 표시는 예약+결제 상태의 조합이라(SA §5-4), 돈이 되돌아간 건을 결제완료로 보여주면 안 된다.
        // 이 동작은 REFUNDED 추가로 자동으로 얻어지는데, 문자열 비교 기반이라 조용히 바뀔 수 있어 고정한다.
        assertThat(ReservationProgressStatus.from(
                ReservationStatus.TREATMENT_COMPLETED,
                "REFUNDED"
        )).isEqualTo(ReservationProgressStatus.TREATMENT_COMPLETED);
    }

    @Test
    @DisplayName("결제 정보가 없거나 미확정이면 예약 상태를 그대로 노출한다")
    void noOrUnsettledPayment_keepsReservationStatus() {
        assertThat(ReservationProgressStatus.from(ReservationStatus.TREATMENT_COMPLETED, null))
                .isEqualTo(ReservationProgressStatus.TREATMENT_COMPLETED);
        assertThat(ReservationProgressStatus.from(ReservationStatus.TREATMENT_COMPLETED, "PENDING"))
                .isEqualTo(ReservationProgressStatus.TREATMENT_COMPLETED);
        assertThat(ReservationProgressStatus.from(ReservationStatus.TREATMENT_COMPLETED, "OFFLINE_REQUIRED"))
                .isEqualTo(ReservationProgressStatus.TREATMENT_COMPLETED);
    }
}
