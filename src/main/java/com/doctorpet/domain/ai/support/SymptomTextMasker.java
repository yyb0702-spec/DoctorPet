package com.doctorpet.domain.ai.support;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 상담 원문을 저장하기 전에 대표적인 개인정보 패턴을 제거한다. */
@Component
public class SymptomTextMasker {

    private static final String MASK = "[MASKED]";
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:01[016789]|02|0[3-6][1-5])[- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)");
    private static final Pattern RESIDENT_NUMBER = Pattern.compile(
            "(?<!\\d)\\d{6}[- ]?[1-4]\\d{6}(?!\\d)");

    public String mask(String symptomText) {
        String masked = EMAIL.matcher(symptomText).replaceAll(MASK);
        masked = PHONE.matcher(masked).replaceAll(MASK);
        return RESIDENT_NUMBER.matcher(masked).replaceAll(MASK);
    }
}
