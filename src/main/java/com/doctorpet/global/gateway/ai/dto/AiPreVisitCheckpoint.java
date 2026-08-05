package com.doctorpet.global.gateway.ai.dto;

import java.util.Arrays;
import java.util.List;

/** 처치 지시 없이 보호자가 관찰할 수 있는 방문 전 확인 항목. */
public enum AiPreVisitCheckpoint {

    ONSET_TIME("증상이 시작된 시점을 확인해 주세요."),
    DURATION("증상이 지속된 시간을 확인해 주세요."),
    FREQUENCY("증상이 나타나는 횟수를 확인해 주세요."),
    APPETITE_CHANGE("평소와 비교한 식욕 변화를 확인해 주세요."),
    WATER_INTAKE("평소와 비교한 음수량 변화를 확인해 주세요."),
    ACTIVITY_CHANGE("평소와 비교한 활동량 변화를 확인해 주세요."),
    PAIN_RESPONSE("특정 부위를 만질 때 보이는 반응을 확인해 주세요."),
    VOMITING_OR_DIARRHEA("구토나 설사의 횟수와 지속 시간을 확인해 주세요."),
    URINATION_OR_DEFECATION("배뇨와 배변의 횟수 및 상태 변화를 확인해 주세요."),
    BREATHING_CHANGE("호흡 속도나 평소와 다른 호흡 모습을 확인해 주세요."),
    VISIBLE_INJURY("눈에 보이는 상처나 부종이 있는지 확인해 주세요."),
    EXPOSURE_HISTORY("평소와 다른 음식이나 물질에 노출됐는지 확인해 주세요.");

    private final String guidance;

    AiPreVisitCheckpoint(String guidance) {
        this.guidance = guidance;
    }

    public String guidance() {
        return guidance;
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    public static String guidanceOf(String value) {
        try {
            return valueOf(value).guidance;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ONSET_TIME.guidance;
        }
    }
}
