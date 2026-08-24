package com.doctorpet.domain.ai.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.ai.entity.AiConsultation;
import com.doctorpet.domain.ai.entity.AiConsultationStatus;
import com.doctorpet.domain.ai.entity.AiToolCallStatus;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/** Level 3 — ai_consultations JSON·enum·nullable 컬럼의 실제 MySQL 영속화 검증. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class AiConsultationDdlIntegrationTest {

    @Autowired
    private AiConsultationRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("성공 상담의 구조화 결과와 중복 조회 필드를 같은 행에 저장한다")
    void success_persistsStructuredResultAndMetrics() {
        AiAnalysisResult result = new AiAnalysisResult(
                List.of("GENERAL"),
                List.of("BLOOD_TEST", "XRAY"),
                UrgencyLevel.MODERATE,
                List.of("증상 시작 시점"),
                true,
                "model-a",
                "dog-v1",
                12,
                20
        );
        AiConsultation saved = repository.saveAndFlush(
                AiConsultation.success(1L, "마스킹된 증상", result, 35));
        entityManager.clear();

        AiConsultation reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getMemberId()).isEqualTo(1L);
        assertThat(reloaded.getStructuredResult().urgencyLevel()).isEqualTo(UrgencyLevel.MODERATE);
        assertThat(reloaded.getRequiredCapabilities()).containsExactly("BLOOD_TEST", "XRAY");
        assertThat(reloaded.getStatus()).isEqualTo(AiConsultationStatus.SUCCESS);
        assertThat(reloaded.getToolCallStatus()).isEqualTo(AiToolCallStatus.SUCCESS);
        assertThat(reloaded.getModel()).isEqualTo("model-a");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("비로그인 실패 상담은 member와 구조화 결과 없이 실패 원인을 저장한다")
    void failure_persistsNullableFieldsAndErrorType() {
        AiConsultation saved = repository.saveAndFlush(AiConsultation.failed(
                null, "마스킹된 증상", AiGatewayFailureReason.TIMEOUT, 100));
        entityManager.clear();

        AiConsultation reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getMemberId()).isNull();
        assertThat(reloaded.getStructuredResult()).isNull();
        assertThat(reloaded.getUrgencyLevel()).isNull();
        assertThat(reloaded.getStatus()).isEqualTo(AiConsultationStatus.FAILED);
        assertThat(reloaded.getErrorType()).isEqualTo(AiGatewayFailureReason.TIMEOUT);
        assertThat(reloaded.isFallbackUsed()).isTrue();
        assertThat(reloaded.isSchemaParseSuccess()).isFalse();
    }

    @Test
    @DisplayName("보존 기간이 지난 상담의 증상 텍스트만 삭제한다")
    void clearSymptomTextsCreatedBefore_clearsOnlyExpiredText() {
        AiAnalysisResult result = new AiAnalysisResult(
                List.of("GENERAL"), List.of(), UrgencyLevel.LOW, List.of(), true,
                null, null, null, null);
        AiConsultation expired = repository.saveAndFlush(
                AiConsultation.success(null, "오래된 증상", result, 10));
        AiConsultation recent = repository.saveAndFlush(
                AiConsultation.success(null, "최근 증상", result, 10));
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        entityManager.createNativeQuery(
                        "update ai_consultations set created_at = :createdAt where id = :id")
                .setParameter("createdAt", cutoff.minusSeconds(1))
                .setParameter("id", expired.getId())
                .executeUpdate();
        entityManager.clear();

        int updatedCount = repository.clearSymptomTextsCreatedBefore(cutoff);

        assertThat(updatedCount).isEqualTo(1);
        assertThat(repository.findById(expired.getId()).orElseThrow().getSymptomText()).isNull();
        assertThat(repository.findById(recent.getId()).orElseThrow().getSymptomText())
                .isEqualTo("최근 증상");
    }
}
