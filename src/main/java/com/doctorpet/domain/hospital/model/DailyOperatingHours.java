package com.doctorpet.domain.hospital.model;

import java.time.LocalTime;

/**
 * 병원의 하루 진료 시작·종료 시간을 표현합니다.
 * 휴무일은 이 객체 대신 요일별 운영시간 Map의 null 값으로 표현합니다.
 */
public record DailyOperatingHours(
        LocalTime openTime,
        LocalTime closeTime
) {

    public DailyOperatingHours {
        if (openTime == null || closeTime == null) {
            throw new IllegalArgumentException(
                    "진료 시작시간과 종료시간이 모두 필요합니다."
            );
        }
    }
}
