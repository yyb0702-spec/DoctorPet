package com.doctorpet.domain.notification.migration;

// 알림 유형 컬럼 VARCHAR 전환이 Hibernate ddl-auto보다 먼저 실행되도록 배선한다(이슈 #176).

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 운영 DB의 notifications.type·resource_type 전환을 Hibernate ddl-auto보다 먼저 실행한다.
 *
 * <p>ddl-auto=update는 기존 컬럼의 타입을 바꿔주지 않으므로, 전환을 JPA 초기화 뒤로 미루면 첫 부팅 동안은
 * 컬럼이 그대로 ENUM인 채 새 값 INSERT가 실패할 수 있다. 이 러너를 대체한 두 ENUM 확장 러너와 같은 배선이고,
 * 그 러너들이 서로 필요로 했던 @DependsOn 실행 순서 강제는 더 이상 필요 없다 — ENUM 정의를 통째로 교체하는
 * 러너가 하나도 남지 않아 "남의 값이 사라지는" 순서 의존이 사라졌다.
 */
@Configuration(proxyBeanMethods = false)
class NotificationTypeVarcharMigrationConfiguration {

    static final String PRE_JPA_MIGRATION_BEAN = "notificationTypeVarcharPreJpaMigration";

    @Bean(name = PRE_JPA_MIGRATION_BEAN)
    InitializingBean notificationTypeVarcharPreJpaMigration(NotificationTypeVarcharMigrationRunner runner) {
        return runner::migrateBeforeJpa;
    }

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor notificationTypeVarcharJpaDependency() {
        return new EntityManagerFactoryDependsOnPostProcessor(PRE_JPA_MIGRATION_BEAN);
    }
}
