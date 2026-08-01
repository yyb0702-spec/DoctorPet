package com.doctorpet.domain.ai.support;

import com.doctorpet.domain.ai.model.AiHospitalSearchIntent;
import java.util.List;
import org.springframework.stereotype.Component;

/** 실제 LLM 연동 전 합의된 표현만 제한적으로 검색 의도로 변환한다. */
@Component
public class AiHospitalSearchIntentExtractor {

    private static final List<String> OPEN_NOW_EXPRESSIONS = List.of(
            "지금", "현재", "바로", "문 연", "진료 가능한");
    private static final List<String> NIGHT_CARE_EXPRESSIONS = List.of(
            "야간", "밤 늦게", "밤늦게", "새벽에", "새벽에도", "24시간");
    private static final List<String> DISTANCE_EXPRESSIONS = List.of(
            "가까운", "가장 가까운", "근처", "주변");

    public AiHospitalSearchIntent extract(String symptomText) {
        return new AiHospitalSearchIntent(
                containsAny(symptomText, OPEN_NOW_EXPRESSIONS),
                containsAny(symptomText, NIGHT_CARE_EXPRESSIONS),
                containsAny(symptomText, DISTANCE_EXPRESSIONS)
        );
    }

    private boolean containsAny(String text, List<String> expressions) {
        return expressions.stream().anyMatch(text::contains);
    }
}
