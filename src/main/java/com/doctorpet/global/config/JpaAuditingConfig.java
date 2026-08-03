package com.doctorpet.global.config;

import com.doctorpet.global.time.TimePolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** BaseEntity의 createdAt/updatedAt 자동 기록을 활성화한다. */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.system(TimePolicy.SEOUL_ZONE_ID);
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock applicationClock) {
        return () -> Optional.<TemporalAccessor>of(LocalDateTime.now(applicationClock));
    }
}
