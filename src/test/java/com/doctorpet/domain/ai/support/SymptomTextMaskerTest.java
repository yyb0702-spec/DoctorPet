package com.doctorpet.domain.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SymptomTextMaskerTest {

    private final SymptomTextMasker masker = new SymptomTextMasker();

    @Test
    @DisplayName("이메일·전화번호·주민등록번호 패턴을 저장 전에 마스킹한다")
    void mask_personalInformationPatterns() {
        String symptom = "연락처 test@example.com, 010-1234-5678, 990101-1234567입니다.";

        String masked = masker.mask(symptom);

        assertThat(masked).isEqualTo("연락처 [MASKED], [MASKED], [MASKED]입니다.");
    }

    @Test
    @DisplayName("개인정보 패턴이 없는 증상은 변경하지 않는다")
    void mask_withoutPersonalInformation_keepsOriginal() {
        assertThat(masker.mask("어제부터 밥을 먹지 않아요."))
                .isEqualTo("어제부터 밥을 먹지 않아요.");
    }
}
