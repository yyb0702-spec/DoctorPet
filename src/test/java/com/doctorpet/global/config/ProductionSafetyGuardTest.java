package com.doctorpet.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ProductionSafetyGuardTest {

    private ProductionSafetyGuard guardWith(String[] activeProfiles, String paymentGateway, String mailProvider) {
        Environment environment = mock(Environment.class);
        given(environment.getActiveProfiles()).willReturn(activeProfiles);

        ProductionSafetyGuard guard = new ProductionSafetyGuard(environment);
        ReflectionTestUtils.setField(guard, "paymentGateway", paymentGateway);
        ReflectionTestUtils.setField(guard, "mailProvider", mailProvider);
        return guard;
    }

    @Test
    void prod_프로파일에서_payment_gateway가_fake면_부팅을_막는다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"prod"}, "fake", "smtp");

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.gateway=fake");
    }

    @Test
    void prod_프로파일에서_mail_provider가_fake면_부팅을_막는다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"prod"}, "portone", "fake");

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mail.provider=fake");
    }

    @Test
    void prod_프로파일에서_둘_다_실연동이면_통과한다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"prod"}, "portone", "smtp");

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    void prod_프로파일이_아니면_fake여도_통과한다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"docker"}, "fake", "fake");

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }
}
