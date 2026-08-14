package com.doctorpet.domain.reservation.migration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 신규 대기열 테이블을 Hibernate ddl-auto보다 먼저 생성해 운영 DDL 경로를 단일화한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "reservation.waitlist-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ReservationWaitlistMigrationConfiguration {

    static final String PRE_JPA_MIGRATION_BEAN = "reservationWaitlistPreJpaMigration";

    @Bean(name = PRE_JPA_MIGRATION_BEAN)
    InitializingBean reservationWaitlistPreJpaMigration(
            ReservationWaitlistSchemaMigrationRunner runner
    ) {
        return runner::migrateBeforeJpa;
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor reservationWaitlistJpaDependency() {
        return new EntityManagerFactoryDependsOnPostProcessor(PRE_JPA_MIGRATION_BEAN);
    }
}
