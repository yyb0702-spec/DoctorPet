package com.doctorpet.global.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
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

    public String resolve(HttpServletRequest request, Set<String> trustedProxies) {
        String remoteAddress = request.getRemoteAddr();
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

    // DNS 해석 실패는 신뢰하지 않음으로 처리한다(fail-closed).
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
