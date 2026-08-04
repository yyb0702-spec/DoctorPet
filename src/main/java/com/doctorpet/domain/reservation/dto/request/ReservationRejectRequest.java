package com.doctorpet.domain.reservation.dto.request;

import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReservationRejectRequest(
        @NotBlank(message = "예약 거절 사유는 필수입니다.")
        @Size(max = 255, message = "예약 거절 사유는 255자 이하여야 합니다.")
        String rejectReason
) {

    @AssertTrue(message = "예약 거절 사유는 직원 부족, 슬롯 등록 오류, 진료 불가, 기타 중 하나여야 합니다.")
    public boolean isSupportedRejectReason() {
        return ReservationRejectReason.supports(rejectReason);
    }

    public ReservationRejectReason toRejectReason() {
        return ReservationRejectReason.fromValue(rejectReason);
    }
}
