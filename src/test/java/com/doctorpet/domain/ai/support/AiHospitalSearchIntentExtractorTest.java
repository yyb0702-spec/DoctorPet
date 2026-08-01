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
}
