package com.doctorpet.domain.chat.migration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** chat_messages 멱등키 마이그레이션을 Hibernate ddl-auto보다 먼저 실행한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "chat.client-message-id-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
class ChatMessageClientMessageIdMigrationConfiguration {

    static final String PRE_JPA_MIGRATION_BEAN = "chatMessageClientMessageIdPreJpaMigration";

    @Bean(name = PRE_JPA_MIGRATION_BEAN)
    InitializingBean chatMessageClientMessageIdPreJpaMigration(
            ChatMessageClientMessageIdMigrationRunner runner
    ) {
        return runner::migrateBeforeJpa;
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor chatMessageClientMessageIdJpaDependency() {
        return new EntityManagerFactoryDependsOnPostProcessor(PRE_JPA_MIGRATION_BEAN);
    }
}
