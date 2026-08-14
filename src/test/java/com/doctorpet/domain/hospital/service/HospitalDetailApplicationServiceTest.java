package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.hospital.dto.query.HospitalResponseMetrics;
import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.reservation.service.HospitalResponseMetricsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HospitalDetailApplicationServiceTest {

    private static final Long HOSPITAL_ID = 1L;

    @Mock
    private HospitalService hospitalService;

    @Mock
    private HospitalResponseMetricsService hospitalResponseMetricsService;

    @InjectMocks
    private HospitalDetailApplicationService applicationService;

    @Test
    void 제휴_병원_상세_API에만_예약_응답_지표를_결합한다() {
        given(hospitalService.getHospitalDetail(HOSPITAL_ID, 10L))
                .willReturn(detail(PartnershipStatus.PARTNER));
        given(hospitalResponseMetricsService.getMetrics(HOSPITAL_ID))
                .willReturn(new HospitalResponseMetrics(80, 15));

        HospitalDetailResponse response =
                applicationService.getHospitalDetail(HOSPITAL_ID, 10L);

        assertThat(response.reservationResponseRate()).isEqualTo(80);
        assertThat(response.averageApprovalMinutes()).isEqualTo(15);
    }

    @Test
    void 비제휴_병원은_예약_응답_지표를_조회하지_않는다() {
        given(hospitalService.getHospitalDetail(HOSPITAL_ID, null))
                .willReturn(detail(PartnershipStatus.NON_PARTNER));

        HospitalDetailResponse response =
                applicationService.getHospitalDetail(HOSPITAL_ID, null);

        assertThat(response.reservationResponseRate()).isNull();
        assertThat(response.averageApprovalMinutes()).isNull();
        verifyNoInteractions(hospitalResponseMetricsService);
    }

    private HospitalDetailResponse detail(PartnershipStatus partnershipStatus) {
        return new HospitalDetailResponse(
                HOSPITAL_ID,
                "테스트 동물병원",
                "서울특별시 중구",
                "02-1234-5678",
                BusinessStatus.OPEN,
                partnershipStatus,
                null,
                partnershipStatus == PartnershipStatus.PARTNER,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                0L,
                false,
                null,
                null
        );
    }
}
