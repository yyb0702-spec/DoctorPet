package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupResult;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupStatus;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HospitalSearchCacheRepositoryTest {

    private static final String CACHE_KEY =
            "hospital-search:initial-page:v1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    private HospitalSearchCacheRepository cacheRepository;

    @BeforeEach
    void setUp() {
        cacheRepository = new HospitalSearchCacheRepository(
                redisTemplate,
                objectMapper
        );
        ReflectionTestUtils.setField(
                cacheRepository,
                "cacheTtl",
                Duration.ofMinutes(10)
        );
        lenient().when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);
    }

    @Test
    void 캐시가_없으면_빈_결과를_반환한다() {
        given(valueOperations.get(CACHE_KEY))
                .willReturn(null);

        assertThat(cacheRepository.findInitialPage().status())
                .isEqualTo(HospitalSearchCacheLookupStatus.MISS);
    }

    @Test
    void 캐시_조회에_실패하면_빈_결과로_대체한다() {
        given(valueOperations.get(CACHE_KEY))
                .willThrow(new RuntimeException("Redis unavailable"));

        assertThat(cacheRepository.findInitialPage().status())
                .isEqualTo(
                        HospitalSearchCacheLookupStatus.UNAVAILABLE
                );
    }

    @Test
    void 캐시_데이터_변환에_실패하면_갱신_가능한_MISS를_반환한다()
            throws Exception {
        given(valueOperations.get(CACHE_KEY))
                .willReturn("invalid json");
        given(objectMapper.readValue(
                "invalid json",
                HospitalSearchCachedPage.class
        )).willThrow(new RuntimeException("Deserialization failed"));

        HospitalSearchCacheLookupResult result =
                cacheRepository.findInitialPage();

        assertThat(result.status())
                .isEqualTo(HospitalSearchCacheLookupStatus.MISS);
        assertThat(result.canWrite()).isTrue();
    }

    @Test
    void 캐시_content가_누락되면_갱신_가능한_MISS를_반환한다()
            throws Exception {
        given(valueOperations.get(CACHE_KEY))
                .willReturn("{\"totalElements\":1}");
        given(objectMapper.readValue(
                "{\"totalElements\":1}",
                HospitalSearchCachedPage.class
        )).willReturn(new HospitalSearchCachedPage(null, 1L));

        HospitalSearchCacheLookupResult result =
                cacheRepository.findInitialPage();

        assertThat(result.status())
                .isEqualTo(HospitalSearchCacheLookupStatus.MISS);
        assertThat(result.canWrite()).isTrue();
    }

    @Test
    void 캐시_후보에_null이_포함되면_MISS를_반환한다()
            throws Exception {
        given(valueOperations.get(CACHE_KEY))
                .willReturn("cached json");
        given(objectMapper.readValue(
                "cached json",
                HospitalSearchCachedPage.class
        )).willReturn(new HospitalSearchCachedPage(
                Arrays.asList((HospitalSearchCandidate) null),
                1L
        ));

        assertThat(cacheRepository.findInitialPage().status())
                .isEqualTo(HospitalSearchCacheLookupStatus.MISS);
    }

    @Test
    void 캐시_후보_수와_전체_건수가_일치하지_않으면_MISS를_반환한다()
            throws Exception {
        given(valueOperations.get(CACHE_KEY))
                .willReturn("cached json");
        given(objectMapper.readValue(
                "cached json",
                HospitalSearchCachedPage.class
        )).willReturn(new HospitalSearchCachedPage(
                List.of(validCandidate()),
                0L
        ));

        assertThat(cacheRepository.findInitialPage().status())
                .isEqualTo(HospitalSearchCacheLookupStatus.MISS);
    }

    @Test
    void 캐시_저장에_실패해도_예외를_전파하지_않는다()
            throws Exception {
        HospitalSearchCachedPage page =
                new HospitalSearchCachedPage(List.of(), 0L);
        given(objectMapper.writeValueAsString(page))
                .willThrow(new RuntimeException("Serialization failed"));

        assertThatCode(() -> cacheRepository.saveInitialPage(page))
                .doesNotThrowAnyException();
    }

    @Test
    void 첫_페이지를_JSON과_TTL로_저장한다()
            throws Exception {
        HospitalSearchCachedPage page =
                new HospitalSearchCachedPage(List.of(), 0L);
        given(objectMapper.writeValueAsString(page))
                .willReturn("{\"content\":[],\"totalElements\":0}");

        cacheRepository.saveInitialPage(page);

        verify(valueOperations).set(
                CACHE_KEY,
                "{\"content\":[],\"totalElements\":0}",
                Duration.ofMinutes(10)
        );
    }

    @Test
    void 공공데이터_갱신_후_첫_페이지_캐시를_삭제한다() {
        cacheRepository.evictInitialPage();

        verify(redisTemplate).delete(CACHE_KEY);
    }

    @Test
    void 캐시_삭제에_실패해도_예외를_전파하지_않는다() {
        given(redisTemplate.delete(CACHE_KEY))
                .willThrow(new RuntimeException("Redis unavailable"));

        assertThatCode(cacheRepository::evictInitialPage)
                .doesNotThrowAnyException();
    }

    private HospitalSearchCandidate validCandidate() {
        return new HospitalSearchCandidate(
                1L,
                "제휴 병원",
                "서울특별시 중구 도로명주소",
                "서울특별시 중구 지번주소",
                null,
                null,
                BusinessStatus.OPEN,
                PartnershipStatus.PARTNER,
                null
        );
    }
}
