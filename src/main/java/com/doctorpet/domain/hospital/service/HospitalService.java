package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HospitalService {

    private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");

    private final HospitalRepository hospitalRepository;
    private final HospitalDetailRepository hospitalDetailRepository;
    private final HospitalCapabilityRepository hospitalCapabilityRepository;

    @Transactional(readOnly = true)
    public HospitalDetailResponse getHospitalDetail(Long hospitalId) {
        Hospital hospital = hospitalRepository.findById(hospitalId)
                .orElseThrow(() -> new ServiceException(HospitalErrorCode.HOSPITAL_NOT_FOUND));

        if (hospital.getPartnershipStatus() != PartnershipStatus.PARTNER) {
            return HospitalDetailResponse.from(
                    hospital,
                    null,
                    null,
                    null
            );
        }

        HospitalDetail detail = hospitalDetailRepository.findByHospital(hospital)
                .orElseThrow(() -> new ServiceException(HospitalErrorCode.HOSPITAL_DETAIL_NOT_FOUND));

        List<HospitalCapability> capabilities = hospitalCapabilityRepository.findAllByHospital(hospital);

        return HospitalDetailResponse.from(
                hospital,
                detail,
                capabilities,
                calculateOpenNow(hospital, detail)
        );
    }

    private boolean calculateOpenNow(
            Hospital hospital,
            HospitalDetail detail
    ) {
        if (hospital.getBusinessStatus()
                != BusinessStatus.OPEN) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        DayOfWeek today = now.getDayOfWeek();
        var operatingHours = detail.getOpenHours().get(today);

        if (operatingHours == null) {
            return false;
        }

        LocalTime currentTime = now.toLocalTime();
        LocalTime openTime = operatingHours.openTime();
        LocalTime closeTime = operatingHours.closeTime();

        if (openTime.equals(closeTime)) {
            return false;
        }

        // 당일 영업(예: 09:00~18:00)은 시작 시각 이상이면서 종료 시각 미만일 때 영업 중입니다.
        if (openTime.isBefore(closeTime)) {
            return !currentTime.isBefore(openTime)
                    && currentTime.isBefore(closeTime);
        }

        // 자정을 넘는 영업(예: 20:00~02:00)은 시작 시각 이후이거나 종료 시각 이전이면 영업 중입니다.
        return !currentTime.isBefore(openTime)
                || currentTime.isBefore(closeTime);
    }
}
