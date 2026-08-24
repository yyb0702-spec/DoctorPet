package com.doctorpet.global.gateway.payment.fake;

import com.doctorpet.global.gateway.payment.PaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FakePaymentGateway 조건부 배선 검증(이슈 #38 리뷰 P1).
 * 핵심: {@code payment.gateway} 설정이 없거나 {@code fake}가 아니면 Fake 빈이 생성되지 않아야 한다
 * — 운영 설정 누락이 곧 미청구 결제 성공으로 이어지는 사고를 막는다.
 */
class FakePaymentGatewayWiringTest {

    @Configuration
    @Import(FakePaymentGateway.class)
    static class FakeOnlyConfig {
    }

    private AnnotationConfigApplicationContext contextWithGateway(String gatewayValue) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        // 시스템 환경변수·시스템 프로퍼티를 상속하지 않는 격리 Environment를 쓴다.
        // 그렇지 않으면 CI/셸에 설정된 PAYMENT_GATEWAY(예: ci.yml의 fake)가 "설정 없음" 시나리오로
        // 새어 들어와 fail-safe 검증이 깨진다 — payment.gateway는 오직 이 테스트가 주입한 값만 본다.
        ctx.setEnvironment(new IsolatedEnvironment());
        if (gatewayValue != null) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("payment.gateway", gatewayValue)));
        }
        ctx.register(FakeOnlyConfig.class);
        ctx.refresh();
        return ctx;
    }

    /** OS 환경변수·시스템 프로퍼티 소스를 배제한 Environment — 조건부 배선을 주입값만으로 검증한다. */
    private static class IsolatedEnvironment extends StandardEnvironment {
        @Override
        protected void customizePropertySources(MutablePropertySources propertySources) {
            // 기본 소스(systemProperties·systemEnvironment)를 추가하지 않는다.
        }
    }

    @Test
    @DisplayName("payment.gateway=fake이면 Fake 게이트웨이가 등록된다")
    void fakeRegisteredWhenExplicitlySelected() {
        try (AnnotationConfigApplicationContext ctx = contextWithGateway("fake")) {
            assertEquals(1, ctx.getBeansOfType(PaymentGateway.class).size());
            assertTrue(ctx.getBeansOfType(FakePaymentGateway.class).size() == 1);
        }
    }

    @Test
    @DisplayName("payment.gateway 설정이 없으면 Fake 게이트웨이가 등록되지 않는다(fail-safe)")
    void fakeAbsentWhenPropertyMissing() {
        try (AnnotationConfigApplicationContext ctx = contextWithGateway(null)) {
            assertTrue(ctx.getBeansOfType(FakePaymentGateway.class).isEmpty(),
                    "설정 누락 시 Fake가 붙으면 미청구 결제가 성공 처리될 수 있다");
        }
    }

    @Test
    @DisplayName("payment.gateway=portone이면 Fake 게이트웨이가 등록되지 않는다")
    void fakeAbsentWhenPortoneSelected() {
        try (AnnotationConfigApplicationContext ctx = contextWithGateway("portone")) {
            assertTrue(ctx.getBeansOfType(FakePaymentGateway.class).isEmpty());
        }
    }
}
