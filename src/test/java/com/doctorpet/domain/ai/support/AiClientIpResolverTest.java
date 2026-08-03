package com.doctorpet.domain.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.ai.config.AiRateLimitProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class AiClientIpResolverTest {

    @Test
    @DisplayName("신뢰하지 않은 요청의 전달 헤더를 무시한다")
    void resolve_untrustedRemote_ignoresForwardedHeader() {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        AiClientIpResolver resolver = new AiClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("신뢰한 프록시 체인의 가장 가까운 비신뢰 주소를 사용한다")
    void resolve_trustedProxy_usesForwardedClientAddress() {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.1", "10.0.0.2"));
        AiClientIpResolver resolver = new AiClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "192.0.2.5, 203.0.113.10, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.10");
    }

    @Test
    @DisplayName("신뢰 프록시가 전달한 잘못된 주소는 건너뛰고 유효한 IPv6 주소를 사용한다")
    void resolve_invalidForwardedAddress_usesValidIpv6Address() {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.1", "10.0.0.2"));
        AiClientIpResolver resolver = new AiClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "2001:db8::1, invalid-address, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("신뢰 프록시의 전달 헤더가 없으면 경고를 남기고 직접 연결 주소를 사용한다")
    void resolve_missingForwardedHeader_warnsAndUsesRemoteAddress(CapturedOutput output) {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.1"));
        AiClientIpResolver resolver = new AiClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
        assertThat(output).contains("X-Forwarded-For 헤더가 없어 직접 연결 주소를 사용합니다");
    }

    @Test
    @DisplayName("전달 헤더에 유효한 클라이언트 IP가 없으면 경고를 남기고 직접 연결 주소를 사용한다")
    void resolve_noValidClientAddress_warnsAndUsesRemoteAddress(CapturedOutput output) {
        AiRateLimitProperties properties = new AiRateLimitProperties();
        properties.setTrustedProxies(List.of("10.0.0.1", "10.0.0.2"));
        AiClientIpResolver resolver = new AiClientIpResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "999.1.1.1, invalid-address, 10.0.0.2");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
        assertThat(output)
                .contains("유효한 클라이언트 IP를 찾지 못해 직접 연결 주소를 사용합니다")
                .contains("invalidAddressCount=2");
    }
}
