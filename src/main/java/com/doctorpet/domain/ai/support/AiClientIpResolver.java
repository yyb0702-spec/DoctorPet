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
        Set<String> trustedProxies = Set.copyOf(
                properties.getTrustedProxies());
        if (!trustedProxies.contains(remoteAddress)) {
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
            if (!trustedProxies.contains(address)) {
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
