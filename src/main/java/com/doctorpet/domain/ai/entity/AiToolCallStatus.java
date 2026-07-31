package com.doctorpet.domain.ai.entity;

/** 병원 검색 Tool 연동 전 상담은 NOT_CALLED로 기록한다. */
public enum AiToolCallStatus {
    NOT_CALLED,
    SUCCESS,
    FAILED
}
