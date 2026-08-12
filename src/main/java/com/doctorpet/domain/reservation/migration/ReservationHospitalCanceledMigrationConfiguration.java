package com.doctorpet.domain.reservation.migration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 병원 취소 legacy enum 마이그레이션을 Hibernate ddl-auto보다 먼저 실행한다. */
@Configuration(proxyBeanMethods = false)
class ReservationHospitalCanceledMigrationConfiguration {

    static final String PRE_JPA_MIGRATION_BEAN =
            "reservationHospitalCanceledPreJpaMigration";

    @Bean(name = PRE_JPA_MIGRATION_BEAN)
    InitializingBean reservationHospitalCanceledPreJpaMigration(
            ReservationHospitalCanceledStatusMigrationRunner runner
    ) {
        return runner::migrateBeforeJpa;
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor
            reservationHospitalCanceledJpaDependency() {
        return new EntityManagerFactoryDependsOnPostProcessor(PRE_JPA_MIGRATION_BEAN);
    }
}
