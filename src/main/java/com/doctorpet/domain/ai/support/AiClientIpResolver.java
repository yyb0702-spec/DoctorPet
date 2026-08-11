package com.doctorpet.domain.ai.support;

import com.doctorpet.domain.ai.config.AiRateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final AiRateLimitProperties properties;

    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        Set<String> trustedProxies = Set.copyOf(properties.getTrustedProxies());
        if (!isTrustedProxy(remoteAddress, trustedProxies)) {
            return remoteAddress;
        }

        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
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

    // trustedProxies 항목은 리터럴 IP뿐 아니라 호스트명(예: 컴포즈 서비스명 "nginx")도 허용한다
    // (리뷰 지적 P1). 이전에는 nginx에 docker-compose.yml networks.default.ipam.config로
    // 고정 IP를 부여하고 그 IP를 여기 신뢰 목록에 하드코딩했는데, 그러면 이미 운영 중인
    // EC2에서(이 PR 배포가 최초 적용될 때) mysql/redis가 서브넷 지정 없던 기존 default
    // 네트워크에 이미 붙어 있어, docker compose가 네트워크 설정 변경을 감지해 재생성을
    // 시도하다 "network has active endpoints"로 실패할 위험이 있었다(무중단 배포 파이프라인
    // 자체가 최초 실행에서 막힘). 고정 IP 대신 컴포즈가 항상 제공하는 서비스명 DNS(예:
    // "nginx")를 신뢰 대상으로 등록하면 네트워크 토폴로지를 전혀 바꾸지 않고도 같은 보호를
    // 얻을 수 있다.
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

    // trustedProxy 항목이 이미 리터럴 IP와 정확히 일치했다면 위 contains에서 먼저 걸러지므로
    // 여기까지 오는 건 실제로 호스트명인 경우뿐이다(순수 IP 문자열은 DNS 조회 없이 그대로
    // 반환되므로 이 호출 자체는 안전하지만 항상 불일치라 결과에 영향이 없다). DNS 해석
    // 실패(UnknownHostException)는 신뢰하지 않음으로 처리한다(fail-closed) — 일시적 DNS 문제로
    // 아무 IP나 통과시키는 쪽보다, 신뢰 판단을 건너뛰고 remoteAddress를 그대로 쓰는 쪽이
    // 안전하다.
    private boolean matchesResolvedHostname(String trustedProxy, String candidate) {
        try {
            for (InetAddress resolved : InetAddress.getAllByName(trustedProxy)) {
                if (resolved.getHostAddress().equals(candidate)) {
                    return true;
                }
            }
        } catch (UnknownHostException exception) {
            log.warn("신뢰 프록시 호스트명을 해석하지 못했습니다. trustedProxy={}", trustedProxy);
        }
        return false;
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
