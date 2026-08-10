package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.domain.review.dto.response.ReviewRatingSummary;
import com.doctorpet.domain.review.service.ReviewQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HospitalServiceTest {

    private static final Long HOSPITAL_ID = 1L;
    private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private HospitalDetailRepository hospitalDetailRepository;

    @Mock
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    @Mock
    private HospitalSearchCacheRepository hospitalSearchCacheRepository;

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
                hospitalFavoriteService,
                reviewQueryService
        );
        lenient().when(reviewQueryService.getRatingSummary(anyLong()))
                .thenReturn(ReviewRatingSummary.empty());
    }

    @Test
    void 제휴_병원이_영업시간이면_상세정보와_영업중_상태를_반환한다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        HospitalDetail detail = createDetail(hospital);
        List<HospitalCapability> capabilities = List.of(
                HospitalCapability.create(hospital, CapabilityValue.DOG),
                HospitalCapability.create(hospital, CapabilityValue.XRAY)
        );
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospitalDetailRepository.findByHospital(hospital))
                .willReturn(Optional.of(detail));
        given(hospitalCapabilityRepository.findAllByHospital(hospital))
                .willReturn(capabilities);

        HospitalDetailResponse response =
                hospitalService.getHospitalDetail(HOSPITAL_ID);

        assertThat(response.openNow()).isTrue();
        assertThat(response.businessHours()).hasSize(7);
        DayOfWeek today = LocalDate.now(SEOUL_ZONE_ID).getDayOfWeek();
        assertThat(response.businessHours())
                .filteredOn(hours -> hours.dayOfWeek() == today)
                .singleElement()
                .extracting("closed")
                .isEqualTo(false);
        assertThat(response.surgeryAvailable()).isTrue();
        assertThat(response.hospitalizationAvailable()).isTrue();
        assertThat(response.nightCare()).isFalse();
        assertThat(response.emergency()).isFalse();
        assertThat(response.capabilities())
                .containsExactly(CapabilityValue.DOG, CapabilityValue.XRAY);
    }

    @Test
    void 병원_상세에_리뷰_평균과_개수를_반환한다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, false);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(reviewQueryService.getRatingSummary(HOSPITAL_ID))
                .willReturn(new ReviewRatingSummary(
                        new BigDecimal("4.3"),
                        3L
                ));

        HospitalDetailResponse response =
                hospitalService.getHospitalDetail(HOSPITAL_ID);

        assertThat(response.averageRating()).isEqualByComparingTo("4.3");
        assertThat(response.reviewCount()).isEqualTo(3L);
    }

    @Test
    void 리뷰가_없는_병원은_평균_null과_개수_0을_반환한다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, false);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));

        HospitalDetailResponse response =
                hospitalService.getHospitalDetail(HOSPITAL_ID);

        assertThat(response.averageRating()).isNull();
        assertThat(response.reviewCount()).isZero();
    }

    @Test
    void 인증된_보호자의_병원_상세에는_찜_여부를_반환한다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, false);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospitalFavoriteService.findFavoriteHospitalIds(
                10L,
                List.of(HOSPITAL_ID)
        )).willReturn(java.util.Set.of(HOSPITAL_ID));

        HospitalDetailResponse response =
                hospitalService.getHospitalDetail(HOSPITAL_ID, 10L);

        assertThat(response.favorite()).isTrue();
    }

    @Test
    void 폐업한_제휴_병원은_운영시간과_관계없이_영업중이_아니다() {
        Hospital hospital = createHospital(BusinessStatus.CLOSED, true);
        HospitalDetail detail = createDetail(hospital);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospitalDetailRepository.findByHospital(hospital))
                .willReturn(Optional.of(detail));
        given(hospitalCapabilityRepository.findAllByHospital(hospital))
                .willReturn(List.of());

        HospitalDetailResponse response =
                hospitalService.getHospitalDetail(HOSPITAL_ID);

        assertThat(response.openNow()).isFalse();
    }

    @Test
    void 자정_이후에는_전날의_심야영업_시간을_확인한다() {
        LocalDateTime tuesdayAtOne =
                LocalDateTime.of(2026, 7, 28, 1, 0);
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        HospitalDetail detail = createDetail(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(20, 0),
                                LocalTime.of(2, 0)
                        )
                )
        );
        givenPartnerHospital(hospital, detail);

        HospitalDetailResponse response =
                getHospitalDetailAt(tuesdayAtOne);

        assertThat(response.openNow()).isTrue();
    }

    @Test
    void 자정_이후에는_아직_시작하지_않은_오늘의_심야영업을_적용하지_않는다() {
        LocalDateTime tuesdayAtOne =
                LocalDateTime.of(2026, 7, 28, 1, 0);
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        HospitalDetail detail = createDetail(
                hospital,
                Map.of(
                        DayOfWeek.TUESDAY,
                        new DailyOperatingHours(
                                LocalTime.of(20, 0),
                                LocalTime.of(2, 0)
                        )
                )
        );
        givenPartnerHospital(hospital, detail);

        HospitalDetailResponse response =
                getHospitalDetailAt(tuesdayAtOne);

        assertThat(response.openNow()).isFalse();
    }

    @Test
    void 심야영업은_시작_시각부터_영업중이다() {
        LocalDateTime mondayAtTwenty =
                LocalDateTime.of(2026, 7, 27, 20, 0);
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        HospitalDetail detail = createDetail(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(20, 0),
                                LocalTime.of(2, 0)
                        )
                )
        );
        givenPartnerHospital(hospital, detail);

        HospitalDetailResponse response =
                getHospitalDetailAt(mondayAtTwenty);

        assertThat(response.openNow()).isTrue();
    }

    @Test
    void 심야영업은_종료_시각부터_영업종료다() {
        LocalDateTime tuesdayAtTwo =
                LocalDateTime.of(2026, 7, 28, 2, 0);
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        HospitalDetail detail = createDetail(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(20, 0),
                                LocalTime.of(2, 0)
                        )
                )
        );
        givenPartnerHospital(hospital, detail);

        HospitalDetailResponse response =
                getHospitalDetailAt(tuesdayAtTwo);

        assertThat(response.openNow()).isFalse();
    }

    @Test
    void 시작과_종료_시각이_같으면_영업중이_아니다() {
        LocalDateTime mondayAtNoon =
                LocalDateTime.of(2026, 7, 27, 12, 0);
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        HospitalDetail detail = createDetail(
                hospital,
                Map.of(
                        DayOfWeek.MONDAY,
                        new DailyOperatingHours(
                                LocalTime.of(9, 0),
                                LocalTime.of(9, 0)
                        )
                )
        );
        givenPartnerHospital(hospital, detail);

        HospitalDetailResponse response =
                getHospitalDetailAt(mondayAtNoon);

        assertThat(response.openNow()).isFalse();
    }

    @Test
    void 비제휴_병원은_제휴_전용_정보를_null로_반환한다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, false);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));

        HospitalDetailResponse response =
                hospitalService.getHospitalDetail(HOSPITAL_ID);

        assertThat(response.partnershipStatus())
                .isEqualTo(PartnershipStatus.NON_PARTNER);
        assertThat(response.partnershipNotice()).isNotBlank();
        assertThat(response.openNow()).isNull();
        assertThat(response.businessHours()).isNull();
        assertThat(response.capabilities()).isNull();
        assertThat(response.surgeryAvailable()).isNull();
        assertThat(response.hospitalizationAvailable()).isNull();
        assertThat(response.nightCare()).isNull();
        assertThat(response.emergency()).isNull();
        verifyNoInteractions(
                hospitalDetailRepository,
                hospitalCapabilityRepository
        );
    }

    @Test
    void 병원이_없으면_HOSPITAL_001을_반환한다() {
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                hospitalService.getHospitalDetail(HOSPITAL_ID)
        )
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.HOSPITAL_NOT_FOUND);
    }

    @Test
    void 운영중인_제휴_병원은_슬롯_조회가_가능하다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));

        boolean available =
                hospitalService.isReservationSlotLookupAvailable(HOSPITAL_ID);

        assertThat(available).isTrue();
    }

    @Test
    void 휴업중인_제휴_병원은_슬롯_조회가_불가능하다() {
        Hospital hospital = createHospital(
                BusinessStatus.CLOSED_TEMP,
                true
        );
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));

        boolean available =
                hospitalService.isReservationSlotLookupAvailable(HOSPITAL_ID);

        assertThat(available).isFalse();
    }

    @Test
    void 비제휴_병원은_슬롯_조회가_불가능하다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, false);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));

        boolean available =
                hospitalService.isReservationSlotLookupAvailable(HOSPITAL_ID);

        assertThat(available).isFalse();
    }

    @Test
    void 제휴_병원의_상세정보가_없으면_HOSPITAL_002를_반환한다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospitalDetailRepository.findByHospital(hospital))
                .willReturn(Optional.empty());

        assertThatThrownBy(() ->
                hospitalService.getHospitalDetail(HOSPITAL_ID)
        )
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.HOSPITAL_DETAIL_NOT_FOUND);
    }

    @Test
    void 예약_목록용_병원_요약은_여러_병원을_한번에_조회한다() {
        Hospital hospital = createHospital(BusinessStatus.OPEN, true);
        given(hospitalRepository.findAllById(List.of(HOSPITAL_ID)))
                .willReturn(List.of(hospital));

        var responses = hospitalService.getHospitalSummaries(
                List.of(HOSPITAL_ID)
        );

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.hospitalId()).isEqualTo(HOSPITAL_ID);
            assertThat(response.name()).isEqualTo("테스트 동물병원");
        });
    }

    private Hospital createHospital(
            BusinessStatus businessStatus,
            boolean partner
    ) {
        Hospital hospital = Hospital.createFromPublicData(
                "TEST-MGMT-NO",
                "TEST-LOCAL-GOV",
                "테스트 동물병원",
                "02-1234-5678",
                "서울특별시 중구 지번주소",
                "서울특별시 중구 도로명주소",
                "01234",
                null,
                null,
                null,
                businessStatus,
                null,
                null,
                null
        );
        ReflectionTestUtils.setField(hospital, "id", HOSPITAL_ID);
        if (partner) {
            hospital.markAsPartner();
        }
        return hospital;
    }

    private HospitalDetail createDetail(Hospital hospital) {
        return createDetail(
                hospital,
                Map.of(
                        LocalDate.now(SEOUL_ZONE_ID).getDayOfWeek(),
                        new DailyOperatingHours(
                                LocalTime.MIN,
                                LocalTime.MAX
                        )
                )
        );
    }

    private HospitalDetail createDetail(
            Hospital hospital,
            Map<DayOfWeek, DailyOperatingHours> openHours
    ) {
        return HospitalDetail.create(
                hospital,
                openHours,
                true,
                true,
                false,
                false
        );
    }

    private void givenPartnerHospital(
            Hospital hospital,
            HospitalDetail detail
    ) {
        given(hospitalRepository.findById(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospitalDetailRepository.findByHospital(hospital))
                .willReturn(Optional.of(detail));
        given(hospitalCapabilityRepository.findAllByHospital(hospital))
                .willReturn(List.of());
    }

    private HospitalDetailResponse getHospitalDetailAt(
            LocalDateTime dateTime
    ) {
        try (MockedStatic<LocalDateTime> mockedDateTime =
                     mockStatic(LocalDateTime.class)) {
            mockedDateTime.when(() -> LocalDateTime.now(SEOUL_ZONE_ID))
                    .thenReturn(dateTime);
            return hospitalService.getHospitalDetail(HOSPITAL_ID);
        }
    }
}
