package com.doctorpet.domain.reservation.entity.status;

import java.util.Arrays;

public enum ReservationRejectReason {

    STAFF_SHORTAGE("직원 부족"),
    SLOT_REGISTRATION_ERROR("슬롯 등록 오류"),
    TREATMENT_UNAVAILABLE("진료 불가"),
    OTHER("기타");

    private final String value;

    ReservationRejectReason(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static boolean supports(String value) {
        return Arrays.stream(values())
                .anyMatch(reason -> reason.value.equals(value));
    }

    public static ReservationRejectReason fromValue(String value) {
        return Arrays.stream(values())
                .filter(reason -> reason.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "지원하지 않는 예약 거절 사유입니다."
                ));
    }
}
