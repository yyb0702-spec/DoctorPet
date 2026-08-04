package com.doctorpet.global.gateway.ai.dto;

import java.util.Arrays;
import java.util.List;

/** AI가 선택할 수 있는 증상 관찰 범위. 질환명이나 진단명은 포함하지 않는다. */
public enum AiFocusArea {

    GENERAL("전반적인 상태"),
    DIGESTIVE("소화기"),
    SKIN("피부"),
    MUSCULOSKELETAL("근골격계"),
    RESPIRATORY("호흡기"),
    URINARY("비뇨기"),
    NEUROLOGICAL("신경계"),
    EYE("눈"),
    EAR("귀"),
    ORAL("구강"),
    BEHAVIORAL("행동 변화");

    private final String displayName;

    AiFocusArea(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    public static String displayNameOf(String value) {
        try {
            return valueOf(value).displayName;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return GENERAL.displayName;
        }
    }
}
