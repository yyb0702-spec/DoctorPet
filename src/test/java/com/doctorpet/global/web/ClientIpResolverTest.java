package com.doctorpet.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * domain.ai.support.AiClientIpResolverTest와 같은 시나리오를 검증한다 — 이 클래스는 그 로직을
 * 도메인에 묶이지 않는 공용 유틸리티로 옮긴 것(ClientIpResolver 클래스 주석 참고)이라, 신뢰
 * 목록을 프로퍼티가 아니라 호출 시점 인자로 받는다는 점만 다르다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    @DisplayName("신뢰하지 않은 요청의 전달 헤더를 무시한다")
    void resolve_untrustedRemote_ignoresForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertThat(resolver.resolve(request, Set.of())).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("신뢰한 프록시 체인의 가장 가까운 비신뢰 주소를 사용한다")
    void resolve_trustedProxy_usesForwardedClientAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "192.0.2.5, 203.0.113.10, 10.0.0.2");

        assertThat(resolver.resolve(request, Set.of("10.0.0.1", "10.0.0.2")))
                .isEqualTo("203.0.113.10");
    }

    @Test
    @DisplayName("신뢰 프록시가 전달한 잘못된 주소는 건너뛰고 유효한 IPv6 주소를 사용한다")
    void resolve_invalidForwardedAddress_usesValidIpv6Address() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "2001:db8::1, invalid-address, 10.0.0.2");

        assertThat(resolver.resolve(request, Set.of("10.0.0.1", "10.0.0.2")))
                .isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("신뢰 프록시의 전달 헤더가 없으면 경고를 남기고 직접 연결 주소를 사용한다")
    void resolve_missingForwardedHeader_warnsAndUsesRemoteAddress(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertThat(resolver.resolve(request, Set.of("10.0.0.1"))).isEqualTo("10.0.0.1");
        assertThat(output).contains("X-Forwarded-For 헤더가 없어 직접 연결 주소를 사용합니다");
    }

    @Test
    @DisplayName("전달 헤더에 유효한 클라이언트 IP가 없으면 경고를 남기고 직접 연결 주소를 사용한다")
    void resolve_noValidClientAddress_warnsAndUsesRemoteAddress(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "999.1.1.1, invalid-address, 10.0.0.2");

        assertThat(resolver.resolve(request, Set.of("10.0.0.1", "10.0.0.2"))).isEqualTo("10.0.0.1");
        assertThat(output)
                .contains("유효한 클라이언트 IP를 찾지 못해 직접 연결 주소를 사용합니다")
                .contains("invalidAddressCount=2");
    }

    @Test
    @DisplayName("신뢰 프록시 목록에 호스트명을 등록하면 해석된 IP로 신뢰 여부를 판단한다")
    void resolve_trustedProxyAsHostname_resolvesAndTrusts() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertThat(resolver.resolve(request, Set.of("localhost"))).isEqualTo("203.0.113.10");
    }

    @Test
    @DisplayName("신뢰 프록시 목록의 호스트명이 해석되지 않으면 신뢰하지 않는다(fail-closed)")
    void resolve_trustedProxyHostnameUnresolvable_treatsAsUntrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        assertThat(resolver.resolve(request, Set.of("definitely-not-a-real-host.invalid")))
                .isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("같은 호스트명을 반복 조회해도 TTL 안에서는 DNS 해석을 다시 하지 않는다(PR 리뷰 지적 P2)")
    void resolve_repeatedHostnameLookup_cachedWithinTtlAndLogsOnce(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "203.0.113.10");

        // 해석 실패(존재하지 않는 호스트명)도 캐시된다 — 매번 재해석했다면 경고 로그가 호출 횟수만큼
        // 찍혀야 하지만, 캐싱되면 첫 호출에서만 DNS 해석을 시도하고 이후엔 캐시된 실패 결과를 쓴다.
        for (int i = 0; i < 20; i++) {
            resolver.resolve(request, Set.of("definitely-not-a-real-host.invalid"));
        }

        int warnCount = output.toString().split("신뢰 프록시 호스트명을 해석하지 못했습니다", -1).length - 1;
        assertThat(warnCount).isEqualTo(1);
    }
}
