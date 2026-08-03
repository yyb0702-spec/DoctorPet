package com.doctorpet.domain.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.ai.model.AiHospitalSearchIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiHospitalSearchIntentExtractorTest {

    private final AiHospitalSearchIntentExtractor extractor =
            new AiHospitalSearchIntentExtractor();

    @Test
    @DisplayName("현재 영업·야간·거리 표현을 허용된 검색 의도로 변환한다")
    void extract_allowedExpressions() {
        AiHospitalSearchIntent intent = extractor.extract(
                "지금 진료 가능한 가장 가까운 24시간 병원을 알려줘");

        assertThat(intent.openNow()).isTrue();
        assertThat(intent.nightCare()).isTrue();
        assertThat(intent.distance()).isTrue();
    }

    @Test
    @DisplayName("새벽인데 지금 진료 가능한 표현은 야간 조건을 자동 적용하지 않는다")
    void extract_dawnCurrentAvailability() {
        AiHospitalSearchIntent intent = extractor.extract("새벽인데 지금 진료 가능한 병원");

        assertThat(intent.openNow()).isTrue();
        assertThat(intent.nightCare()).isFalse();
        assertThat(intent.distance()).isFalse();
    }

    @Test
    @DisplayName("새벽에 지금 진료 가능한 요청은 단순 새벽 키워드를 야간 조건으로 중복 적용하지 않는다")
    void extract_dawnExpressionWithCurrentAvailability() {
        AiHospitalSearchIntent intent = extractor.extract("새벽에 지금 진료 가능한 병원");

        assertThat(intent.openNow()).isTrue();
        assertThat(intent.nightCare()).isFalse();
        assertThat(intent.distance()).isFalse();
    }

    @Test
    @DisplayName("현재 영업과 24시간 진료를 명시하면 두 조건을 함께 적용한다")
    void extract_explicitCurrentAndNightCare() {
        AiHospitalSearchIntent intent = extractor.extract("지금 문 연 24시간 병원");

        assertThat(intent.openNow()).isTrue();
        assertThat(intent.nightCare()).isTrue();
        assertThat(intent.distance()).isFalse();
    }
}
