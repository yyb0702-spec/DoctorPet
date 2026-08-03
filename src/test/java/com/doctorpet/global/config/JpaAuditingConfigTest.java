package com.doctorpet.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.global.time.TimePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.DateTimeProvider;

class JpaAuditingConfigTest {

    @Test
    @DisplayName("JPA 감사 시각을 애플리케이션 서울 Clock으로 생성한다")
    void auditingDateTimeProvider_usesApplicationClock() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-03T00:00:00Z"),
                TimePolicy.SEOUL_ZONE_ID
        );
        DateTimeProvider provider = new JpaAuditingConfig().auditingDateTimeProvider(clock);

        assertThat(provider.getNow()).contains(LocalDateTime.of(2026, 8, 3, 9, 0));
    }
}
