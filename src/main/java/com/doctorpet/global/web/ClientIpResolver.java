package com.doctorpet.global.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 리버스 프록시(nginx) 뒤에서 실제 클라이언트 IP를 신뢰 가능하게 해석하는 공용 유틸리티다.
 * {@code domain.ai.support.AiClientIpResolver}에서 XFF 스푸핑 방어를 위해 먼저 만들어졌던
 * 로직을 그대로 옮긴 것이다(기능 구멍 점검 대응 — 인증메일 rate limit도 같은 신뢰 경계 문제를
 * 겪어, 도메인마다 따로 베껴 쓰는 대신 여기로 공통화했다). AiClientIpResolver는 하위 호환을
 * 위해 그대로 두고 이 클래스에는 의존하지 않는다 — 이미 여러 차례 리뷰를 거쳐 검증된 코드라
 * 손대지 않는 게 더 안전하다고 판단했다.
 *
 * 호출부가 신뢰할 프록시 목록(trustedProxies)을 그때그때 넘겨받는 구조다 — 이 클래스 자체는
 * 특정 도메인의 ConfigurationProperties에 묶이지 않는다.
 *
 * remoteAddr가 trustedProxies에 없으면 X-Forwarded-For를 신뢰하지 않고 remoteAddr를 그대로
 * 반환한다. remoteAddr가 신뢰 대상이면 X-Forwarded-For를 오른쪽(가장 최근에 추가된 값)부터
 * 왼쪽으로 훑어, 유효한 IP 중 trustedProxies에 없는 첫 값을 실제 클라이언트 IP로 본다 — 이
 * 값보다 왼쪽(더 앞)에 있는 값들은 클라이언트가 스스로 주장한 값일 뿐이라 신뢰하지 않는다.
 */
@Slf4j
@Component
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    // 신뢰 프록시 호스트명(예: "nginx")은 요청마다 InetAddress.getAllByName()으로 다시 해석하면
    // 인증메일 rate limit처럼 매 요청 호출되는 경로에서 매번 DNS 왕복이 생긴다(PR 리뷰 지적 P2).
    // 짧은 TTL로 캐싱하면서도 컴포즈 서비스명이 재배포로 다른 IP를 받는 경우를 위해 무한정 캐시하지
    // 않는다 — 캐시가 오래돼도 최악의 경우 다음 요청에서 재해석할 뿐이라 안전 쪽으로 치우친 설계다.
    private static final Duration DNS_CACHE_TTL = Duration.ofSeconds(60);

    private final ConcurrentHashMap<String, ResolvedHostnameCacheEntry> resolvedHostnameCache =
            new ConcurrentHashMap<>();

    public String resolve(HttpServletRequest request, Set<String> trustedProxies) {
        HttpServletRequest rawRequest = unwrapToRawRequest(request);
        String remoteAddress = rawRequest.getRemoteAddr();
        if (!isTrustedProxy(remoteAddress, trustedProxies)) {
            return remoteAddress;
        }

        String forwardedFor = rawRequest.getHeader(X_FORWARDED_FOR);
        if (!StringUtils.hasText(forwardedFor)) {
            log.warn(
                    "신뢰 프록시 요청에 X-Forwarded-For 헤더가 없어 직접 연결 주소를 사용합니다. "
                            + "remoteAddress={}",
                    remoteAddress
            );
            return remoteAddress;
        }

        List<String> forwardedAddresses = List.of(forwardedFor.split(","));
        int invalidAddressCount = 0;
        for (int index = forwardedAddresses.size() - 1; index >= 0; index--) {
            String address = forwardedAddresses.get(index).trim();
            if (!isValidIpAddress(address)) {
                invalidAddressCount++;
                continue;
            }
            if (!isTrustedProxy(address, trustedProxies)) {
                return address;
            }
        }

        log.warn(
                "신뢰 프록시 요청의 X-Forwarded-For 헤더에서 유효한 클라이언트 IP를 찾지 못해 "
                        + "직접 연결 주소를 사용합니다. remoteAddress={}, invalidAddressCount={}",
                remoteAddress,
                invalidAddressCount
        );
        return remoteAddress;
    }

    // (PR 리뷰 지적 P1, 이슈 #181) server.forward-headers-strategy: framework가 켜져 있으면
    // Spring의 ForwardedHeaderFilter가 이 클래스보다 먼저(필터 체인 최상단 근처) 실행되며,
    // X-Forwarded-For의 첫 값으로 request.getRemoteAddr()를 덮어쓴다. nginx는
    // $proxy_add_x_forwarded_for를 써서 클라이언트가 보낸 XFF를 보존한 채 실제 연결 주소를
    // 뒤에 추가하므로, 공격자가 요청마다 XFF의 첫 값을 바꾸면 ForwardedHeaderFilter가 그
    // 스푸핑된 값을 그대로 remoteAddr로 승격시킨다. 그러면 바로 아래 isTrustedProxy(remoteAddress,
    // ...) 검사가 nginx를 거치지 않은 직접 연결로 오판해, 스푸핑된 값을 검증 없이 그대로
    // 반환해버려 IP별 rate limit이 무력화된다.
    //
    // remoteAddr만 원본으로 되돌리는 것으로는 충분하지 않다 — ForwardedHeaderFilter가 만드는
    // 래핑된 요청은 getHeader("X-Forwarded-For")도 항상 null을 반환하도록 재정의한다(다운스트림이
    // 이미 처리된 forwarded 헤더를 또 읽고 새어나가지 않게 하려는 의도적 동작,
    // ForwardedHeaderRemovingRequest 참고). remoteAddr만 원본으로 풀고 X-Forwarded-For는 여전히
    // 래핑된 요청에서 읽으면, "신뢰 프록시가 맞지만 XFF 헤더가 없다"는 분기로 빠져 모든 요청이
    // nginx 자신의 주소로 통째로 뭉뚱그려진다(전체 사용자가 같은 rate limit 버킷을 공유하게
    // 되는 훨씬 심각한 회귀 — 실제로 이 문제를 잡아준 회귀 테스트 실패로 발견했다). 그래서
    // remoteAddr와 X-Forwarded-For 둘 다 같은 원본(언래핑된) 요청에서 읽어야 한다.
    //
    // ServletRequestWrapper 체인을 원본 요청까지 풀어, ForwardedHeaderFilter를 포함해 어떤
    // 필터도 손대지 않은 진짜 요청 객체를 신뢰 판정·XFF 파싱 기준으로 삼는다 —
    // forward-headers-strategy 설정이나 앞으로 추가될 다른 래핑 필터와 무관하게 항상 안전하다.
    private HttpServletRequest unwrapToRawRequest(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return (HttpServletRequest) current;
    }

    // trustedProxies 항목은 리터럴 IP뿐 아니라 호스트명(예: 컴포즈 서비스명 "nginx")도 허용한다.
    private boolean isTrustedProxy(String candidate, Set<String> trustedProxies) {
        if (trustedProxies.contains(candidate)) {
            return true;
        }
        for (String trustedProxy : trustedProxies) {
            if (matchesResolvedHostname(trustedProxy, candidate)) {
                return true;
            }
        }
        return false;
    }

    // DNS 해석 실패는 신뢰하지 않음으로 처리한다(fail-closed). 해석 결과(성공/실패 모두)를
    // TTL 동안 캐싱해 요청마다 반복되는 DNS 조회를 피한다(PR 리뷰 지적 P2).
    private boolean matchesResolvedHostname(String trustedProxy, String candidate) {
        return resolveHostnameCached(trustedProxy).contains(candidate);
    }

    private Set<String> resolveHostnameCached(String trustedProxy) {
        long now = System.currentTimeMillis();
        ResolvedHostnameCacheEntry cached = resolvedHostnameCache.get(trustedProxy);
        if (cached != null && cached.expiresAtEpochMillis() > now) {
            return cached.resolvedAddresses();
        }

        Set<String> resolvedAddresses = resolveHostname(trustedProxy);
        resolvedHostnameCache.put(
                trustedProxy,
                new ResolvedHostnameCacheEntry(resolvedAddresses, now + DNS_CACHE_TTL.toMillis())
        );
        return resolvedAddresses;
    }

    private Set<String> resolveHostname(String trustedProxy) {
        try {
            return List.of(InetAddress.getAllByName(trustedProxy)).stream()
                    .map(InetAddress::getHostAddress)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (UnknownHostException exception) {
            log.warn("신뢰 프록시 호스트명을 해석하지 못했습니다. trustedProxy={}", trustedProxy);
            return Set.of();
        }
    }

    // DNS 해석 결과를 TTL과 함께 보관하는 캐시 항목. 실패(빈 Set)도 그대로 캐싱해 존재하지 않는
    // 호스트명에 대한 반복 조회도 TTL 동안 막는다.
    private record ResolvedHostnameCacheEntry(Set<String> resolvedAddresses, long expiresAtEpochMillis) {
    }

    private boolean isValidIpAddress(String address) {
        if (!StringUtils.hasText(address)) {
            return false;
        }
        if (address.contains(":")) {
            return isValidIpv6Address(address);
        }
        return isValidIpv4Address(address);
    }

    private boolean isValidIpv4Address(String address) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return false;
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidIpv6Address(String address) {
        if (!address.matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            InetAddress inetAddress = InetAddress.getByName(address);
            return inetAddress instanceof Inet6Address;
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
