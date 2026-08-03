package com.doctorpet.domain.ai.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** LLM 호출 전에 서버 관리 키워드로 응급 증상을 감지한다. */
@Component
public class EmergencyKeywordDetector {

    private final List<String> keywords;

    public EmergencyKeywordDetector(
            @Value("classpath:ai/emergency-keywords.txt") Resource keywordResource
    ) {
        this.keywords = readKeywords(keywordResource);
    }

    public boolean isEmergency(String symptomText) {
        return keywords.stream().anyMatch(symptomText::contains);
    }

    private List<String> readKeywords(Resource resource) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("응급 키워드 리소스를 읽을 수 없습니다.", exception);
        }
    }
}
