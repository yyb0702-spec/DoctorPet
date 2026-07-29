package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupResult;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

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

    private HospitalService hospitalService;

    @BeforeEach
    void setUp() {
        hospitalService = new HospitalService(
                hospitalRepository,
                hospitalDetailRepository,
                hospitalCapabilityRepository,
                hospitalSearchCacheRepository
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
                BusinessStatus.CLOSED_TEMP,
                false,
                "126.9700",
                "37.5600"
        );
        given(hospitalRepository.count(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(2L);
        given(hospitalRepository.search(
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
        given(hospitalRepository.search(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
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
    void 비제휴_병원은_예약할_수_없고_openNow를_null로_반환한다() {
        Hospital hospital = createHospital(
                1L,
                "비제휴 병원",
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
        given(hospitalRepository.search(
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
        given(hospitalRepository.search(
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
        given(hospitalRepository.search(
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
    void 마지막_페이지보다_큰_페이지를_요청하면_검증_예외를_던진다() {
        Hospital hospital = createHospital(
                1L,
                "가병원",
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
                2,
                20,
                "name"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    void 검색_결과가_없어도_첫_페이지는_정상_빈_응답을_반환한다() {
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
                true,
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
}
