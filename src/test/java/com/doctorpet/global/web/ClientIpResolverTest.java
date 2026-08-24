package com.doctorpet.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

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

    @Test
    @DisplayName(
            "ForwardedHeaderFilter가 remoteAddr를 XFF 첫 값으로 덮어써도 "
                    + "스푸핑된 값으로 신뢰 판정을 우회할 수 없다(PR 리뷰 지적 P1, 이슈 #181)"
    )
    void resolve_forwardedHeaderFilterActive_ignoresSpoofedLeadingXffEntry() throws Exception {
        // server.forward-headers-strategy: framework가 켜지면 이 클래스보다 먼저 실행되는
        // ForwardedHeaderFilter가 X-Forwarded-For의 첫 값으로 request.getRemoteAddr()를
        // 덮어쓴다. nginx는 $proxy_add_x_forwarded_for로 클라이언트가 보낸 XFF를 보존한 채
        // 실제 연결 주소(여기서는 198.51.100.50)를 뒤에 추가하므로, 공격자가 매 요청 XFF의
        // 첫 값을 바꿔도(1.1.1.1 → 9.9.9.9) rate limit 키로 쓰이는 최종 해석 결과는 항상
        // nginx가 실제로 덧붙인 마지막 값과 같아야 한다 — 그렇지 않으면(고친 rawRemoteAddr
        // 없이 request.getRemoteAddr()를 그대로 썼다면) 공격자가 이 값을 자유롭게 조작해
        // IP별 rate limit을 우회할 수 있다.
        assertThat(resolveThroughForwardedHeaderFilter("1.1.1.1, 198.51.100.50"))
                .isEqualTo("198.51.100.50");
        assertThat(resolveThroughForwardedHeaderFilter("9.9.9.9, 198.51.100.50"))
                .isEqualTo("198.51.100.50");
    }

    // MockHttpServletRequest를 실제 org.springframework.web.filter.ForwardedHeaderFilter에
    // 통과시켜, 이 필터가 만드는 (getRemoteAddr()가 재정의된) 래핑된 요청 객체를 그대로
    // ClientIpResolver에 넘긴다 — 필터 체인 순서까지 재현해야 이번 회귀를 실제로 잡아낼 수
    // 있다(단순 MockHttpServletRequest만으로는 이 필터의 remoteAddr 재정의 자체가 재현되지
    // 않아 통과해버린다).
    private String resolveThroughForwardedHeaderFilter(String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // 신뢰 프록시(nginx)의 실제 TCP peer 주소
        request.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> wrapped = new AtomicReference<>();
        new ForwardedHeaderFilter()
                .doFilter(request, response, (req, res) -> wrapped.set((HttpServletRequest) req));

        return resolver.resolve(wrapped.get(), Set.of("10.0.0.1"));
    }
}
