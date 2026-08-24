package com.doctorpet.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ProductionSafetyGuardTest {

    private ProductionSafetyGuard guardWith(
            String[] activeProfiles, String paymentGateway, String mailProvider, String imageStorageProvider
    ) {
        Environment environment = mock(Environment.class);
        given(environment.getActiveProfiles()).willReturn(activeProfiles);

        ProductionSafetyGuard guard = new ProductionSafetyGuard(environment);
        ReflectionTestUtils.setField(guard, "paymentGateway", paymentGateway);
        ReflectionTestUtils.setField(guard, "mailProvider", mailProvider);
        ReflectionTestUtils.setField(guard, "imageStorageProvider", imageStorageProvider);
        return guard;
    }

    @Test
    void prod_프로파일에서_payment_gateway가_fake면_부팅을_막는다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"prod"}, "fake", "smtp", "s3");

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.gateway=fake");
    }

    @Test
    void prod_프로파일에서_mail_provider가_fake면_부팅을_막는다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"prod"}, "portone", "fake", "s3");

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mail.provider=fake");
    }

    @Test
    void prod_프로파일에서_image_storage_provider가_fake면_부팅을_막는다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"prod"}, "portone", "smtp", "fake");

        assertThatThrownBy(guard::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("image.storage.provider=fake");
    }

    @Test
    void prod_프로파일에서_셋_다_실연동이면_통과한다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"prod"}, "portone", "smtp", "s3");

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    void prod_프로파일이_아니면_fake여도_통과한다() {
        ProductionSafetyGuard guard = guardWith(new String[]{"docker"}, "fake", "fake", "fake");

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }
}
