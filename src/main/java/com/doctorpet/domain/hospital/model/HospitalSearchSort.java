package com.doctorpet.domain.hospital.model;

import java.util.Locale;

/** 병원 검색 API와 AI 검색 Tool이 공통으로 사용하는 정렬 기준. */
public enum HospitalSearchSort {

    NAME("name"),
    DISTANCE("distance");

    private final String requestValue;

    HospitalSearchSort(String requestValue) {
        this.requestValue = requestValue;
    }

    public String requestValue() {
        return requestValue;
    }

    public static HospitalSearchSort from(String value) {
        if (value == null) {
            return NAME;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
