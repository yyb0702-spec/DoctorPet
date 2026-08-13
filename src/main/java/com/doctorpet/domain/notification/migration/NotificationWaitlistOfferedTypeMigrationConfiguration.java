package com.doctorpet.domain.notification.migration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 운영 DB의 notifications.type ENUM 확장이 Hibernate ddl-auto보다 먼저 실행되도록 보장한다. */
@Configuration(proxyBeanMethods = false)
class NotificationWaitlistOfferedTypeMigrationConfiguration {

    static final String PRE_JPA_MIGRATION_BEAN = "notificationWaitlistOfferedTypePreJpaMigration";

    @Bean(name = PRE_JPA_MIGRATION_BEAN)
    InitializingBean notificationWaitlistOfferedTypePreJpaMigration(
            NotificationWaitlistOfferedTypeMigrationRunner runner
    ) {
        return runner::migrateBeforeJpa;
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor notificationWaitlistOfferedTypeJpaDependency() {
        return new EntityManagerFactoryDependsOnPostProcessor(PRE_JPA_MIGRATION_BEAN);
    }
}
