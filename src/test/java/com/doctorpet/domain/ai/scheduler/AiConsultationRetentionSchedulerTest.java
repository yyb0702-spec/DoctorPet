package com.doctorpet.domain.ai.scheduler;

import static org.mockito.Mockito.verify;

import com.doctorpet.domain.ai.config.AiRetentionProperties;
import com.doctorpet.domain.ai.repository.AiConsultationRepository;
import com.doctorpet.global.time.TimePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiConsultationRetentionSchedulerTest {

    @Mock
    private AiConsultationRepository repository;

    @Test
    @DisplayName("JVM 기본 시간대와 무관하게 감사 시각과 같은 Clock으로 보존 기준을 계산한다")
    void clearExpiredSymptomTexts_usesApplicationClock() {
        AiRetentionProperties properties = new AiRetentionProperties();
        properties.setSymptomTextDays(30);
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-03T00:00:00Z"),
                TimePolicy.SEOUL_ZONE_ID
        );
        AiConsultationRetentionScheduler scheduler =
                new AiConsultationRetentionScheduler(repository, properties, clock);

        scheduler.clearExpiredSymptomTexts();

        verify(repository).clearSymptomTextsCreatedBefore(
                LocalDateTime.of(2026, 7, 4, 9, 0));
    }
}
