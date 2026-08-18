package com.doctorpet.domain.notification.migration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/** 운영 DB의 notifications.type ENUM 확장이 Hibernate ddl-auto보다 먼저 실행되도록 보장한다. */
@Configuration(proxyBeanMethods = false)
class NotificationReservationRequestedTypeMigrationConfiguration {

    static final String PRE_JPA_MIGRATION_BEAN = "notificationReservationRequestedTypePreJpaMigration";

    // 오타 값 정정을 포함하는 대기열 유형 마이그레이션보다 반드시 뒤에 실행한다. 순서가 뒤집히면 그 러너가
    // 컬럼을 자기 목표 ENUM(RESERVATION_REQUESTED 없음)으로 되돌려 방금 추가한 값이 사라진다.
    @Bean(name = PRE_JPA_MIGRATION_BEAN)
    @DependsOn(NotificationWaitlistOfferedTypeMigrationConfiguration.PRE_JPA_MIGRATION_BEAN)
    InitializingBean notificationReservationRequestedTypePreJpaMigration(
            NotificationReservationRequestedTypeMigrationRunner runner
    ) {
        return runner::migrateBeforeJpa;
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor notificationReservationRequestedTypeJpaDependency() {
        return new EntityManagerFactoryDependsOnPostProcessor(PRE_JPA_MIGRATION_BEAN);
    }
}
