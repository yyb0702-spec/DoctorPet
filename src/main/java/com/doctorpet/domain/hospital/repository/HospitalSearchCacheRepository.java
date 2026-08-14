package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupResult;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Slf4j
@Repository
@RequiredArgsConstructor
public class HospitalSearchCacheRepository {

    private static final String INITIAL_PAGE_KEY =
            "hospital-search:initial-page:v1";
    private static final int INITIAL_PAGE_SIZE = 20;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${hospital.search.cache-ttl:10m}")
    private Duration cacheTtl;

    public HospitalSearchCacheLookupResult findInitialPage() {
        String cachedValue;

        try {
            cachedValue = redisTemplate.opsForValue()
                    .get(INITIAL_PAGE_KEY);
        } catch (Exception exception) {
            log.warn(
                    "병원 검색 첫 페이지 캐시 조회에 실패하여 DB 조회로 대체합니다. key={}",
                    INITIAL_PAGE_KEY,
                    exception
            );
            return HospitalSearchCacheLookupResult.unavailable();
        }

        if (cachedValue == null) {
            return HospitalSearchCacheLookupResult.miss();
        }

        try {
            HospitalSearchCachedPage cachedPage = objectMapper.readValue(
                    cachedValue,
                    HospitalSearchCachedPage.class
            );

            if (!isValidCachedPage(cachedPage)) {
                log.warn(
                        "병원 검색 첫 페이지 캐시 데이터가 유효하지 않아 DB 조회 후 갱신합니다. key={}",
                        INITIAL_PAGE_KEY
                );
                return HospitalSearchCacheLookupResult.miss();
            }

            return HospitalSearchCacheLookupResult.hit(cachedPage);
        } catch (Exception exception) {
            log.warn(
                    "병원 검색 첫 페이지 캐시 데이터 변환에 실패하여 DB 조회 후 갱신합니다. key={}",
                    INITIAL_PAGE_KEY,
                    exception
            );
            return HospitalSearchCacheLookupResult.miss();
        }
    }

    private boolean isValidCachedPage(
            HospitalSearchCachedPage cachedPage
    ) {
        if (cachedPage == null
                || cachedPage.content() == null
                || cachedPage.totalElements() < 0) {
            return false;
        }

        long expectedContentSize = Math.min(
                cachedPage.totalElements(),
                INITIAL_PAGE_SIZE
        );

        return cachedPage.content().size() == expectedContentSize
                && cachedPage.content().stream()
                        .allMatch(this::isValidCandidate);
    }

    private boolean isValidCandidate(
            HospitalSearchCandidate candidate
    ) {
        return candidate != null
                && candidate.hospitalId() != null
                && candidate.name() != null
                && !candidate.name().isBlank()
                && candidate.businessStatus() == BusinessStatus.OPEN
                && candidate.partnershipStatus() != null;
    }

    public void saveInitialPage(HospitalSearchCachedPage page) {
        try {
            redisTemplate.opsForValue().set(
                    INITIAL_PAGE_KEY,
                    objectMapper.writeValueAsString(page),
                    cacheTtl
            );
        } catch (Exception exception) {
            log.warn(
                    "병원 검색 첫 페이지 캐시 저장에 실패했습니다. key={}",
                    INITIAL_PAGE_KEY,
                    exception
            );
        }
    }

    public void evictInitialPage() {
        try {
            redisTemplate.delete(INITIAL_PAGE_KEY);
        } catch (Exception exception) {
            log.warn(
                    "병원 검색 첫 페이지 캐시 삭제에 실패했습니다. key={}",
                    INITIAL_PAGE_KEY,
                    exception
            );
        }
    }
}
