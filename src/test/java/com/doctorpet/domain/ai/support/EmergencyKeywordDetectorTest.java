package com.doctorpet.domain.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class EmergencyKeywordDetectorTest {

    private final EmergencyKeywordDetector detector = new EmergencyKeywordDetector(
            new ClassPathResource("ai/emergency-keywords.txt"));

    @Test
    @DisplayName("응급 키워드가 포함된 증상을 감지한다")
    void isEmergency_detectsKeyword() {
        assertThat(detector.isEmergency("강아지가 갑자기 호흡곤란을 보여요")).isTrue();
    }

    @Test
    @DisplayName("일반 증상은 응급으로 분류하지 않는다")
    void isEmergency_ignoresGeneralSymptom() {
        assertThat(detector.isEmergency("어제부터 밥을 조금 덜 먹어요")).isFalse();
    }
}
