package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.reservation.service.HospitalResponseMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 병원 상세 API에 필요한 병원 정보와 예약 응답 지표를 조합한다. */
@Service
@RequiredArgsConstructor
public class HospitalDetailApplicationService {

    private final HospitalService hospitalService;
    private final HospitalResponseMetricsService hospitalResponseMetricsService;

    public HospitalDetailResponse getHospitalDetail(Long hospitalId, Long memberId) {
        HospitalDetailResponse detail = hospitalService.getHospitalDetail(hospitalId, memberId);
        if (detail.partnershipStatus() != PartnershipStatus.PARTNER) {
            return detail;
        }
        return detail.withResponseMetrics(hospitalResponseMetricsService.getMetrics(hospitalId));
    }
}
