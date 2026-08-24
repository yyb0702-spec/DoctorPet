package com.doctorpet.domain.reservation.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

class ReservationHospitalCanceledMigrationConfigurationTest {

    @Test
    @DisplayName("병원 취소 선행 마이그레이션은 entityManagerFactory보다 먼저 실행된다")
    void entityManagerFactory_dependsOnPreJpaMigration() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(
                "entityManagerFactory",
                new RootBeanDefinition(LocalContainerEntityManagerFactoryBean.class)
        );

        ReservationHospitalCanceledMigrationConfiguration
                .reservationHospitalCanceledJpaDependency()
                .postProcessBeanFactory(beanFactory);

        assertThat(beanFactory.getBeanDefinition("entityManagerFactory").getDependsOn())
                .contains(ReservationHospitalCanceledMigrationConfiguration.PRE_JPA_MIGRATION_BEAN);
    }
}
