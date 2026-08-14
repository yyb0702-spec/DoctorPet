package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.model.CapabilityMatchMode;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.domain.hospital.repository.HospitalTemporaryClosureRepository;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupResult;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.domain.review.service.ReviewQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HospitalSearchServiceTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private HospitalDetailRepository hospitalDetailRepository;

    @Mock
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    @Mock
    private HospitalSearchCacheRepository hospitalSearchCacheRepository;

    @Mock
    private HospitalTemporaryClosureRepository temporaryClosureRepository;

    @Mock
    private ReviewQueryService reviewQueryService;

    @Mock
    private HospitalFavoriteService hospitalFavoriteService;

    private HospitalService hospitalService;

    @BeforeEach
    void setUp() {
        hospitalService = new HospitalService(
                hospitalRepository,
                hospitalDetailRepository,
                hospitalCapabilityRepository,
                hospitalSearchCacheRepository,
                temporaryClosureRepository,
                hospitalFavoriteService,
                reviewQueryService,
                null
        );
    }

    @Test
    void 검색_조건을_저장소에_전달하고_이름순으로_페이지를_반환한다() {
        Hospital second = createHospital(
                2L,
                "나병원",
                BusinessStatus.OPEN,
                false,
                "126.9800",
                "37.5700"
        );
        Hospital first = createHospital(
                1L,
                "가병원",
                BusinessStatus.OPEN,
                false,
                "126.9700",
                "37.5600"
        );
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(2L);
        given(hospitalRepository.searchPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(1)
        )).willReturn(List.of(
                candidate(first)
        ));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        "병원",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        1,
                        1,
                        "name"
                );

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).name()).isEqualTo("가병원");
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isFalse();

        ArgumentCaptor<HospitalSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(HospitalSearchCondition.class);
        verify(hospitalRepository).count(conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().keyword())
                .isEqualTo("병원");
    }

    @Test
    void 사용자_좌표가_있으면_반경_밖_병원을_제외하고_거리순으로_반환한다() {
        Hospital nearby = createHospital(
                1L,
                "가까운 병원",
                BusinessStatus.OPEN,
                false,
                "126.9780",
                "37.5665"
        );
        Hospital farAway = createHospital(
                2L,
                "먼 병원",
                BusinessStatus.OPEN,
                false,
                "127.2000",
                "37.7000"
        );
        given(hospitalRepository.searchAll(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(CapabilityMatchMode.ALL)
        )).willReturn(List.of(
                candidate(farAway),
                candidate(nearby)
        ));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        new BigDecimal("5"),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        1,
                        20,
                        "distance"
                );

        assertThat(response.content())
                .singleElement()
                .satisfies(hospital -> {
                    assertThat(hospital.hospitalId()).isEqualTo(1L);
                    assertThat(hospital.distanceKm())
                            .isEqualByComparingTo("0.0");
                });
        assertThat(response.totalElements()).isEqualTo(1);
    }

    @Test
    void 반올림하면_반경과_같아지는_반경_밖_병원도_제외한다() {
        Hospital outsideRadius = createHospital(
                1L,
                "반경 밖 병원",
                BusinessStatus.OPEN,
                false,
                "126.9780",
                "37.57585"
        );
        given(hospitalRepository.searchAll(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(CapabilityMatchMode.ALL)
        )).willReturn(List.of(candidate(outsideRadius)));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        new BigDecimal("1.0"),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        1,
                        20,
                        "distance"
                );

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void 비제휴_병원은_예약할_수_없고_openNow를_null로_반환한다() {
        Hospital hospital = createHospital(
                1L,
                "비제휴 병원",
                BusinessStatus.OPEN,
                false,
                null,
                null
        );
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.miss());
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);
        given(hospitalRepository.searchPartnerFirstPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(20)
        )).willReturn(List.of(
                candidate(hospital)
        ));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        1,
                        20,
                        "name"
                );

        assertThat(response.content())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.partnershipStatus())
                            .isEqualTo(PartnershipStatus.NON_PARTNER);
                    assertThat(result.reservationAvailable()).isFalse();
                    assertThat(result.partnershipBadge())
                            .isEqualTo("제휴 전 병원");
                    assertThat(result.openNow()).isNull();
                });
    }

    @Test
    void 최초_진입_페이지가_캐시에_있으면_DB를_조회하지_않는다() {
        Hospital hospital = createHospital(
                1L,
                "제휴 병원",
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.hit(
                        new HospitalSearchCachedPage(
                                List.of(candidate(hospital)),
                                1L
                        )
                ));

        HospitalSearchPageResponse response =
                searchInitialPage();

        assertThat(response.content())
                .singleElement()
                .extracting("name")
                .isEqualTo("제휴 병원");
        assertThat(response.totalElements()).isEqualTo(1L);
        verifyNoInteractions(hospitalRepository);
        verify(hospitalSearchCacheRepository, never())
                .saveInitialPage(
                        org.mockito.ArgumentMatchers.any()
                );
    }

    @Test
    void 캐시_HIT_결과에도_인증된_보호자의_찜_여부를_별도로_결합한다() {
        Hospital hospital = createHospital(
                1L,
                "제휴 병원",
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.hit(
                        new HospitalSearchCachedPage(
                                List.of(candidate(hospital)),
                                1L
                        )
                ));
        given(hospitalFavoriteService.findFavoriteHospitalIds(
                10L,
                List.of(1L)
        )).willReturn(java.util.Set.of(1L));

        HospitalSearchPageResponse response = hospitalService.hospitalSearch(
                10L,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "name"
        );

        assertThat(response.content())
                .singleElement()
                .satisfies(result -> assertThat(result.favorite()).isTrue());
        verifyNoInteractions(hospitalRepository);
    }

    @Test
    void 캐시_MISS의_DB_결과에도_보호자의_찜_여부를_결합한다() {
        Hospital hospital = createHospital(
                1L,
                "제휴 병원",
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.miss());
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);
        given(hospitalRepository.searchPartnerFirstPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(20)
        )).willReturn(List.of(candidate(hospital)));
        given(hospitalFavoriteService.findFavoriteHospitalIds(
                10L,
                List.of(1L)
        )).willReturn(java.util.Set.of(1L));

        HospitalSearchPageResponse response = searchInitialPage(10L);

        assertThat(response.content())
                .singleElement()
                .satisfies(result -> assertThat(result.favorite()).isTrue());
        verify(hospitalSearchCacheRepository).saveInitialPage(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void 같은_캐시_결과도_회원별_찜_상태가_서로_섞이지_않는다() {
        Hospital hospital = createHospital(
                1L,
                "제휴 병원",
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        HospitalSearchCachedPage cachedPage = new HospitalSearchCachedPage(
                List.of(candidate(hospital)),
                1L
        );
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.hit(cachedPage));
        given(hospitalFavoriteService.findFavoriteHospitalIds(
                10L,
                List.of(1L)
        )).willReturn(java.util.Set.of(1L));
        given(hospitalFavoriteService.findFavoriteHospitalIds(
                20L,
                List.of(1L)
        )).willReturn(java.util.Set.of());

        HospitalSearchPageResponse firstMember = searchInitialPage(10L);
        HospitalSearchPageResponse secondMember = searchInitialPage(20L);

        assertThat(firstMember.content().get(0).favorite()).isTrue();
        assertThat(secondMember.content().get(0).favorite()).isFalse();
    }

    @Test
    void 최초_진입_페이지가_캐시에_없으면_DB_결과를_캐시에_저장한다() {
        Hospital hospital = createHospital(
                1L,
                "제휴 병원",
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.miss());
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);
        given(hospitalRepository.searchPartnerFirstPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(20)
        )).willReturn(List.of(candidate(hospital)));

        HospitalSearchPageResponse response =
                searchInitialPage();

        assertThat(response.totalElements()).isEqualTo(1L);
        ArgumentCaptor<HospitalSearchCachedPage> cachedPageCaptor =
                ArgumentCaptor.forClass(HospitalSearchCachedPage.class);
        verify(hospitalSearchCacheRepository)
                .saveInitialPage(cachedPageCaptor.capture());
        assertThat(cachedPageCaptor.getValue().content())
                .singleElement()
                .extracting("hospitalId")
                .isEqualTo(1L);
        assertThat(cachedPageCaptor.getValue().totalElements())
                .isEqualTo(1L);
    }

    @Test
    void Redis_조회가_실패하면_DB_결과를_반환하고_캐시_저장을_생략한다() {
        Hospital hospital = createHospital(
                1L,
                "제휴 병원",
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.unavailable());
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);
        given(hospitalRepository.searchPartnerFirstPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(20)
        )).willReturn(List.of(candidate(hospital)));

        HospitalSearchPageResponse response =
                searchInitialPage();

        assertThat(response.content())
                .singleElement()
                .extracting("hospitalId")
                .isEqualTo(1L);
        verify(hospitalSearchCacheRepository, never())
                .saveInitialPage(
                        org.mockito.ArgumentMatchers.any()
                );
    }

    @Test
    void 기본_페이지_크기가_아니면_일반_이름순으로_조회한다() {
        Hospital hospital = createHospital(
                1L,
                "일반 검색 병원",
                BusinessStatus.OPEN,
                false,
                null,
                null
        );
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);
        given(hospitalRepository.searchPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(10)
        )).willReturn(List.of(candidate(hospital)));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        1,
                        10,
                        "name"
                );

        assertThat(response.content())
                .singleElement()
                .extracting("hospitalId")
                .isEqualTo(1L);
        verifyNoInteractions(hospitalSearchCacheRepository);
    }

    @Test
    void 기본_페이지_크기의_뒤쪽_페이지도_제휴_우선으로_조회한다() {
        Hospital hospital = createHospital(
                21L,
                "두 번째 페이지 병원",
                BusinessStatus.OPEN,
                false,
                null,
                null
        );
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(21L);
        given(hospitalRepository.searchPartnerFirstPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(20L),
                org.mockito.ArgumentMatchers.eq(20)
        )).willReturn(List.of(candidate(hospital)));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        2,
                        20,
                        "name"
                );

        assertThat(response.content())
                .singleElement()
                .extracting("hospitalId")
                .isEqualTo(21L);
        verifyNoInteractions(hospitalSearchCacheRepository);
    }

    @Test
    void 지원축종이_DOG나_CAT이_아니면_검증_예외를_던진다() {
        assertThatThrownBy(() -> hospitalService.hospitalSearch(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of("XRAY"),
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "name"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 위도와_경도_중_하나만_전달하면_검증_예외를_던진다() {
        assertThatThrownBy(() -> hospitalService.hospitalSearch(
                null,
                null,
                new BigDecimal("37.5665"),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "name"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 좌표_없이_거리순을_요청하면_검증_예외를_던진다() {
        assertThatThrownBy(() -> hospitalService.hospitalSearch(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "distance"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 마지막_페이지보다_큰_페이지를_요청하면_빈_목록을_반환한다() {
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        2,
                        20,
                        "name"
                );

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.first()).isFalse();
        assertThat(response.last()).isTrue();
        verify(hospitalRepository, never()).searchPartnerFirstPage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void 거리순_검색의_마지막_페이지보다_큰_페이지도_빈_목록을_반환한다() {
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        2,
                        20,
                        "distance"
                );

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.last()).isTrue();
    }

    @Test
    void 거리순_검색의_페이지_오프셋이_int_범위를_넘어도_빈_목록을_반환한다() {
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(1L);

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        Integer.MAX_VALUE,
                        100,
                        "distance"
                );

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.last()).isTrue();
    }

    @Test
    void 검색_결과가_없어도_첫_페이지는_정상_빈_응답을_반환한다() {
        given(hospitalSearchCacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.miss());
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(0L);

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        1,
                        20,
                        "name"
                );

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.totalPages()).isZero();
        assertThat(response.first()).isTrue();
        assertThat(response.last()).isTrue();
    }

    @Test
    void 거리순_검색은_DB에서_정렬한_페이지만_조회한다() {
        Hospital nearby = createHospital(
                1L,
                "가까운 병원",
                BusinessStatus.OPEN,
                false,
                "126.9780",
                "37.5665"
        );
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(2L);
        given(hospitalRepository.searchDistancePage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("37.5665")),
                org.mockito.ArgumentMatchers.eq(new BigDecimal("126.9780")),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(1)
        )).willReturn(List.of(candidate(nearby)));

        HospitalSearchPageResponse response = hospitalService.hospitalSearch(
                null,
                null,
                new BigDecimal("37.5665"),
                new BigDecimal("126.9780"),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                1,
                "distance"
        );

        assertThat(response.content())
                .singleElement()
                .extracting("hospitalId")
                .isEqualTo(1L);
        assertThat(response.totalElements()).isEqualTo(2L);
        verify(hospitalRepository, never()).searchAll(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void 현재_영업_검색은_고정_크기_배치로_후보를_조회한다() {
        List<HospitalSearchCandidate> firstBatch = new ArrayList<>();
        for (long id = 1; id < 200; id++) {
            firstBatch.add(candidate(createHospital(
                    id,
                    "비제휴 병원 " + id,
                    BusinessStatus.OPEN,
                    false,
                    null,
                    null
            )));
        }
        firstBatch.add(openCandidate(200L, "영업 병원 A"));

        given(hospitalRepository.searchPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(200)
        )).willReturn(firstBatch);
        given(hospitalRepository.searchPage(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                ),
                org.mockito.ArgumentMatchers.eq(200L),
                org.mockito.ArgumentMatchers.eq(200)
        )).willReturn(List.of(openCandidate(201L, "영업 병원 B")));

        HospitalSearchPageResponse response = hospitalService.hospitalSearch(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                false,
                true,
                2,
                1,
                "name"
        );

        assertThat(response.content())
                .singleElement()
                .satisfies(hospital -> {
                    assertThat(hospital.hospitalId()).isEqualTo(201L);
                    assertThat(hospital.openNow()).isTrue();
                });
        assertThat(response.totalElements()).isEqualTo(2L);
        assertThat(response.totalPages()).isEqualTo(2);
        verify(hospitalRepository, never()).searchAll(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void 임시_휴무인_제휴_병원은_현재_영업_검색에서_제외한다() {
        Hospital hospital = createHospital(
                1L,
                "임시 휴무 병원",
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        given(hospitalRepository.searchPage(
                org.mockito.ArgumentMatchers.any(HospitalSearchCondition.class),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(200)
        )).willReturn(List.of(candidate(hospital)));
        given(temporaryClosureRepository.findClosures(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyCollection()
        )).willReturn(List.of(HospitalTemporaryClosure.create(
                hospital,
                LocalDate.now(ZoneId.of("Asia/Seoul"))
        )));

        HospitalSearchPageResponse response = hospitalService.hospitalSearch(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                false,
                true,
                1,
                20,
                "name"
        );

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    private Hospital createHospital(
            Long id,
            String name,
            BusinessStatus businessStatus,
            boolean partner,
            String longitude,
            String latitude
    ) {
        Hospital hospital = Hospital.createFromPublicData(
                "MGMT-" + id,
                "LOCAL-GOV",
                name,
                "02-1234-5678",
                "서울특별시 중구 지번주소",
                "서울특별시 중구 도로명주소",
                "01234",
                longitude == null ? null : new BigDecimal(longitude),
                latitude == null ? null : new BigDecimal(latitude),
                null,
                businessStatus,
                null,
                null,
                null
        );
        ReflectionTestUtils.setField(hospital, "id", id);
        if (partner) {
            hospital.markAsPartner();
        }
        return hospital;
    }

    private HospitalSearchPageResponse searchInitialPage() {
        return hospitalService.hospitalSearch(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "name"
        );
    }

    private HospitalSearchPageResponse searchInitialPage(Long memberId) {
        return hospitalService.hospitalSearch(
                memberId,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "name"
        );
    }

    private HospitalSearchCandidate candidate(Hospital hospital) {
        return new HospitalSearchCandidate(
                hospital.getId(),
                hospital.getName(),
                hospital.getAddressRoad(),
                hospital.getAddressJibun(),
                hospital.getCoordX(),
                hospital.getCoordY(),
                hospital.getBusinessStatus(),
                hospital.getPartnershipStatus(),
                null
        );
    }

    private HospitalSearchCandidate openCandidate(Long id, String name) {
        Hospital hospital = createHospital(
                id,
                name,
                BusinessStatus.OPEN,
                true,
                null,
                null
        );
        return new HospitalSearchCandidate(
                hospital.getId(),
                hospital.getName(),
                hospital.getAddressRoad(),
                hospital.getAddressJibun(),
                hospital.getCoordX(),
                hospital.getCoordY(),
                hospital.getBusinessStatus(),
                hospital.getPartnershipStatus(),
                Map.of(
                        LocalDate.now(ZoneId.of("Asia/Seoul")).getDayOfWeek(),
                        new DailyOperatingHours(
                                LocalTime.MIN,
                                LocalTime.MAX
                        )
                )
        );
    }
}
