package com.doctorpet.domain.ai.service;

import com.doctorpet.domain.ai.config.AiRateLimitProperties;
import com.doctorpet.domain.ai.exception.AiErrorCode;
import com.doctorpet.domain.ai.exception.AiRateLimitStorageException;
import com.doctorpet.domain.ai.repository.AiRateLimitRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.time.TimePolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiRateLimiter {

    private static final String KEY_PREFIX = "ai-consultation:rate-limit:";

    private final AiRateLimitRepository repository;
    private final AiRateLimitProperties properties;
    private final AiRateLimitFailureLogger failureLogger;

    public void check(Long memberId, String clientIp) {
        try {
            if (memberId != null) {
                checkAuthenticated(memberId);
            } else {
                checkAnonymous(clientIp);
            }
        } catch (ServiceException exception) {
            failureLogger.logRecovery();
            throw exception;
        } catch (AiRateLimitStorageException exception) {
            failureLogger.logFailure(exception);
            return;
        }
        failureLogger.logRecovery();
    }

    private void checkAuthenticated(Long memberId) {
        long count = repository.increment(
                KEY_PREFIX + "member:minute:" + memberId,
                properties.getMinuteWindow()
        );
        rejectWhenExceeded(count, properties.getAuthenticatedPerMinute());
    }

    private void checkAnonymous(String clientIp) {
        String clientKey = hash(clientIp);
        long minuteCount = repository.increment(
                KEY_PREFIX + "anonymous:minute:" + clientKey,
                properties.getMinuteWindow()
        );
        rejectWhenExceeded(minuteCount, properties.getAnonymousPerMinute());

        ZonedDateTime now = ZonedDateTime.now(TimePolicy.SEOUL_ZONE_ID);
        LocalDate today = now.toLocalDate();
        Duration untilTomorrow = Duration.between(
                now,
                today.plusDays(1).atStartOfDay(TimePolicy.SEOUL_ZONE_ID)
        );
        long dailyCount = repository.increment(
                KEY_PREFIX + "anonymous:day:" + today + ":" + clientKey,
                untilTomorrow
        );
        rejectWhenExceeded(dailyCount, properties.getAnonymousPerDay());
    }

    private void rejectWhenExceeded(long count, int limit) {
        if (count > limit) {
            throw new ServiceException(AiErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
