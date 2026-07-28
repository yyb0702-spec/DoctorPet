package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
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

@ExtendWith(MockitoExtension.class)
class HospitalSearchServiceTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private HospitalDetailRepository hospitalDetailRepository;

    @Mock
    private HospitalCapabilityRepository hospitalCapabilityRepository;

    private HospitalService hospitalService;

    @BeforeEach
    void setUp() {
        hospitalService = new HospitalService(
                hospitalRepository,
                hospitalDetailRepository,
                hospitalCapabilityRepository
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
        given(hospitalRepository.search(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(List.of(
                new HospitalSearchCandidate(second, null),
                new HospitalSearchCandidate(first, null)
        ));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        "병원",
                        null,
                        null,
                        null,
                        List.of(),
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
        verify(hospitalRepository).search(conditionCaptor.capture());
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
                new HospitalSearchCandidate(farAway, null),
                new HospitalSearchCandidate(nearby, null)
        ));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780"),
                        new BigDecimal("5"),
                        List.of(),
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
        given(hospitalRepository.search(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(List.of(
                new HospitalSearchCandidate(hospital, null)
        ));

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        null,
                        null,
                        List.of(),
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
                    assertThat(result.openNow()).isNull();
                });
    }

    @Test
    void 위도와_경도_중_하나만_전달하면_검증_예외를_던진다() {
        assertThatThrownBy(() -> hospitalService.hospitalSearch(
                null,
                new BigDecimal("37.5665"),
                null,
                null,
                List.of(),
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
                List.of(),
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
        given(hospitalRepository.search(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(List.of(
                new HospitalSearchCandidate(hospital, null)
        ));

        assertThatThrownBy(() -> hospitalService.hospitalSearch(
                null,
                null,
                null,
                null,
                List.of(),
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
        given(hospitalRepository.search(
                org.mockito.ArgumentMatchers.any(
                        HospitalSearchCondition.class
                )
        )).willReturn(List.of());

        HospitalSearchPageResponse response =
                hospitalService.hospitalSearch(
                        null,
                        null,
                        null,
                        null,
                        List.of(),
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
}
