package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class HospitalSearchCacheRepository {

    private static final String INITIAL_PAGE_KEY =
            "hospital-search:initial-page:v1";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${hospital.search.cache-ttl:10m}")
    private Duration cacheTtl;

    public Optional<HospitalSearchCachedPage> findInitialPage() {
        try {
            String cachedValue = redisTemplate.opsForValue()
                    .get(INITIAL_PAGE_KEY);

            if (cachedValue == null) {
                return Optional.empty();
            }

            return Optional.of(objectMapper.readValue(
                    cachedValue,
                    HospitalSearchCachedPage.class
            ));
        } catch (Exception exception) {
            log.warn(
                    "병원 검색 첫 페이지 캐시 조회에 실패하여 DB 조회로 대체합니다. key={}",
                    INITIAL_PAGE_KEY,
                    exception
            );
            return Optional.empty();
        }
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
}
