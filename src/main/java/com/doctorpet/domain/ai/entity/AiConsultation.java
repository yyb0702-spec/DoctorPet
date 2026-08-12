package com.doctorpet.domain.ai.entity;

import com.doctorpet.domain.ai.model.AiStructuredResult;
import com.doctorpet.global.gateway.ai.AiGatewayFailureReason;
import com.doctorpet.global.gateway.ai.dto.AiAnalysisResult;
import com.doctorpet.global.gateway.ai.dto.UrgencyLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** AI 상담 로그와 운영·비용 측정값(SA §4 ai_consultations). */
@Getter
@Entity
@Table(name = "ai_consultations")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiConsultation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "symptom_text", columnDefinition = "text")
    private String symptomText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_result", columnDefinition = "json")
    private AiStructuredResult structuredResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_capabilities", columnDefinition = "json")
    private List<String> requiredCapabilities;

    @Enumerated(EnumType.STRING)
    @Column(name = "urgency_level", length = 20)
    private UrgencyLevel urgencyLevel;

    @Column(length = 100)
    private String model;

    @Column(name = "prompt_version", length = 100)
    private String promptVersion;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiConsultationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 40)
    private AiGatewayFailureReason errorType;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Enumerated(EnumType.STRING)
    @Column(name = "tool_call_status", nullable = false, length = 20)
    private AiToolCallStatus toolCallStatus;

    @Column(name = "schema_parse_success", nullable = false)
    private boolean schemaParseSuccess;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private AiConsultation(Long memberId, String symptomText, int latencyMs) {
        this.memberId = memberId;
        this.symptomText = symptomText;
        this.latencyMs = latencyMs;
        this.toolCallStatus = AiToolCallStatus.NOT_CALLED;
    }

    public static AiConsultation success(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            int latencyMs
    ) {
        AiConsultation consultation = new AiConsultation(memberId, maskedSymptomText, latencyMs);
        consultation.structuredResult = AiStructuredResult.from(result);
        consultation.requiredCapabilities = List.copyOf(result.requiredCapabilities());
        consultation.urgencyLevel = result.urgencyLevel();
        consultation.model = result.model();
        consultation.promptVersion = result.promptVersion();
        consultation.promptTokens = result.promptTokens();
        consultation.completionTokens = result.completionTokens();
        consultation.status = AiConsultationStatus.SUCCESS;
        consultation.fallbackUsed = false;
        consultation.toolCallStatus = AiToolCallStatus.SUCCESS;
        consultation.schemaParseSuccess = true;
        return consultation;
    }

    public static AiConsultation successWithoutTool(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            int latencyMs
    ) {
        AiConsultation consultation = success(memberId, maskedSymptomText, result, latencyMs);
        consultation.toolCallStatus = AiToolCallStatus.NOT_CALLED;
        return consultation;
    }

    public static AiConsultation toolFailed(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            int latencyMs
    ) {
        AiConsultation consultation = new AiConsultation(memberId, maskedSymptomText, latencyMs);
        consultation.structuredResult = AiStructuredResult.from(result);
        consultation.requiredCapabilities = List.copyOf(result.requiredCapabilities());
        consultation.urgencyLevel = result.urgencyLevel();
        consultation.model = result.model();
        consultation.promptVersion = result.promptVersion();
        consultation.promptTokens = result.promptTokens();
        consultation.completionTokens = result.completionTokens();
        consultation.status = AiConsultationStatus.FAILED;
        consultation.fallbackUsed = true;
        consultation.toolCallStatus = AiToolCallStatus.FAILED;
        consultation.schemaParseSuccess = true;
        return consultation;
    }

    public static AiConsultation failed(
            Long memberId,
            String maskedSymptomText,
            AiGatewayFailureReason errorType,
            int latencyMs
    ) {
        AiConsultation consultation = new AiConsultation(memberId, maskedSymptomText, latencyMs);
        consultation.status = AiConsultationStatus.FAILED;
        consultation.errorType = errorType;
        consultation.fallbackUsed = true;
        consultation.schemaParseSuccess = false;
        return consultation;
    }

    public static AiConsultation responseValidationFailed(
            Long memberId,
            String maskedSymptomText,
            AiAnalysisResult result,
            AiGatewayFailureReason errorType,
            int latencyMs
    ) {
        AiConsultation consultation = new AiConsultation(memberId, maskedSymptomText, latencyMs);
        consultation.structuredResult = AiStructuredResult.from(result);
        consultation.requiredCapabilities = List.copyOf(result.requiredCapabilities());
        consultation.urgencyLevel = result.urgencyLevel();
        consultation.model = result.model();
        consultation.promptVersion = result.promptVersion();
        consultation.promptTokens = result.promptTokens();
        consultation.completionTokens = result.completionTokens();
        consultation.status = AiConsultationStatus.FAILED;
        consultation.errorType = errorType;
        consultation.fallbackUsed = true;
        consultation.toolCallStatus = AiToolCallStatus.SUCCESS;
        consultation.schemaParseSuccess = false;
        return consultation;
    }
}
