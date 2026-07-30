package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupResult;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupStatus;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class HospitalSearchCacheRepositoryIntegrationTest {

    private static final String CACHE_KEY =
            "hospital-search:initial-page:v1";

    @Autowired
    private HospitalSearchCacheRepository cacheRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(CACHE_KEY);
    }

    @Test
    void 실제_Redis에_후보_목록과_전체_건수를_저장하고_조회한다() {
        HospitalSearchCandidate candidate =
                new HospitalSearchCandidate(
                        1L,
                        "제휴 병원",
                        "서울특별시 중구 도로명주소",
                        "서울특별시 중구 지번주소",
                        new BigDecimal("126.9780"),
                        new BigDecimal("37.5665"),
                        BusinessStatus.OPEN,
                        PartnershipStatus.PARTNER,
                        Map.of(
                                DayOfWeek.MONDAY,
                                new DailyOperatingHours(
                                        LocalTime.of(9, 0),
                                        LocalTime.of(18, 0)
                                )
                        )
                );
        HospitalSearchCachedPage expected =
                new HospitalSearchCachedPage(
                        List.of(candidate),
                        1L
                );

        cacheRepository.saveInitialPage(expected);

        assertThat(cacheRepository.findInitialPage().cachedPageOptional())
                .contains(expected);
        assertThat(redisTemplate.getExpire(CACHE_KEY))
                .isPositive();
    }

    @Test
    void content가_누락된_유효한_JSON은_캐시_MISS로_처리한다() {
        redisTemplate.opsForValue().set(
                CACHE_KEY,
                "{\"totalElements\":1}"
        );

        assertThat(cacheRepository.findInitialPage().status())
                .isEqualTo(HospitalSearchCacheLookupStatus.MISS);
    }
}
