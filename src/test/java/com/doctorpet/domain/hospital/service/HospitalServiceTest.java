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
import com.doctorpet.global.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
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
        return HospitalDetail.create(
                hospital,
                Map.of(
                        LocalDate.now(SEOUL_ZONE_ID).getDayOfWeek(),
                        new DailyOperatingHours(
                                LocalTime.MIN,
                                LocalTime.MAX
                        )
                ),
                true,
                true,
                false,
                false
        );
    }
}
