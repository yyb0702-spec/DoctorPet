package com.doctorpet.global.gateway.ai.fake;

import com.doctorpet.global.gateway.ai.AiGateway;
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

class FakeAiGatewayWiringTest {

    @Configuration
    @Import(FakeAiGateway.class)
    static class FakeOnlyConfig {
    }

    @Test
    @DisplayName("ai.gateway=fake이면 Fake AI 게이트웨이가 등록된다")
    void fakeRegisteredWhenExplicitlySelected() {
        try (AnnotationConfigApplicationContext context = contextWithGateway("fake")) {
            assertEquals(1, context.getBeansOfType(AiGateway.class).size());
            assertEquals(1, context.getBeansOfType(FakeAiGateway.class).size());
        }
    }

    @Test
    @DisplayName("ai.gateway 설정이 없으면 Fake AI 게이트웨이가 등록되지 않는다")
    void fakeAbsentWhenPropertyMissing() {
        try (AnnotationConfigApplicationContext context = contextWithGateway(null)) {
            assertTrue(context.getBeansOfType(FakeAiGateway.class).isEmpty());
        }
    }

    @Test
    @DisplayName("ai.gateway가 fake가 아니면 Fake AI 게이트웨이가 등록되지 않는다")
    void fakeAbsentWhenAnotherGatewaySelected() {
        try (AnnotationConfigApplicationContext context = contextWithGateway("provider")) {
            assertTrue(context.getBeansOfType(FakeAiGateway.class).isEmpty());
        }
    }

    private AnnotationConfigApplicationContext contextWithGateway(String gatewayValue) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(new IsolatedEnvironment());
        if (gatewayValue != null) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("ai.gateway", gatewayValue))
            );
        }
        context.register(FakeOnlyConfig.class);
        context.refresh();
        return context;
    }

    private static class IsolatedEnvironment extends StandardEnvironment {
        @Override
        protected void customizePropertySources(MutablePropertySources propertySources) {
            // 테스트가 명시한 설정만으로 조건부 배선을 검증한다.
        }
    }
}
