package com.doctorpet.domain.ai.scheduler;

import com.doctorpet.domain.ai.config.AiRetentionProperties;
import com.doctorpet.domain.ai.repository.AiConsultationRepository;
import com.doctorpet.global.time.TimePolicy;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiConsultationRetentionScheduler {

    private final AiConsultationRepository repository;
    private final AiRetentionProperties properties;

    @Scheduled(cron = "${ai.retention.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    @Transactional
    public void clearExpiredSymptomTexts() {
        LocalDateTime cutoff = LocalDateTime.now(TimePolicy.SEOUL_ZONE_ID)
                .minusDays(properties.getSymptomTextDays());
        int updatedCount = repository.clearSymptomTextsCreatedBefore(cutoff);
        log.info(
                "보존 기간이 지난 AI 상담 증상 텍스트를 삭제했습니다. retentionDays={}, count={}",
                properties.getSymptomTextDays(),
                updatedCount
        );
    }
}
