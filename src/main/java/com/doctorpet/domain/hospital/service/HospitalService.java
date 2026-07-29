package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSummaryResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

@Slf4j
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
                .orElseThrow(() -> {
                    log.error(
                            "제휴 병원의 상세정보가 없습니다. hospitalId={}",
                            hospitalId
                    );
                    return new ServiceException(
                            HospitalErrorCode.HOSPITAL_DETAIL_NOT_FOUND
                    );
                });

        List<HospitalCapability> capabilities = hospitalCapabilityRepository.findAllByHospital(hospital);

        return HospitalDetailResponse.from(
                hospital,
                detail,
                capabilities,
                calculateOpenNow(hospital, detail)
        );
    }

    /**
     * 예약 목록처럼 여러 병원의 표시용 이름이 필요한 도메인을 위한 배치 조회 계약.
     */
    @Transactional(readOnly = true)
    public List<HospitalSummaryResponse> getHospitalSummaries(
            Collection<Long> hospitalIds
    ) {
        return hospitalRepository.findAllById(hospitalIds).stream()
                .map(HospitalSummaryResponse::from)
                .toList();
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
        LocalTime currentTime = now.toLocalTime();
        var todayHours = detail.getOpenHours().get(today);

        // 오늘 일정은 당일 시작 시각 이후 구간만 판단합니다.
        // 예: 화요일 20:00~02:00은 화요일 01:00이 아니라 20:00부터 적용됩니다.
        if (isOpenDuringTodayHours(todayHours, currentTime)) {
            return true;
        }

        // 자정 이후에는 전날 시작한 심야영업이 이어질 수 있으므로 전날 일정도 확인합니다.
        // 예: 월요일 20:00~02:00이면 화요일 01:00에도 영업 중입니다.
        DayOfWeek yesterday = today.minus(1);
        var yesterdayHours = detail.getOpenHours().get(yesterday);

        return isOpenAfterMidnight(yesterdayHours, currentTime);
    }

    private boolean isOpenDuringTodayHours(
            DailyOperatingHours operatingHours,
            LocalTime currentTime
    ) {
        if (operatingHours == null) {
            return false;
        }

        LocalTime openTime = operatingHours.openTime();
        LocalTime closeTime = operatingHours.closeTime();

        if (openTime.equals(closeTime)) {
            return false;
        }

        // 일반 영업은 시작 시각을 포함하고 종료 시각은 포함하지 않습니다.
        if (openTime.isBefore(closeTime)) {
            return !currentTime.isBefore(openTime)
                    && currentTime.isBefore(closeTime);
        }

        // 자정을 넘는 오늘 영업은 오늘 시작 시각 이후 구간만 여기서 판단합니다.
        // 자정 이후 종료 시각 이전 구간은 isOpenAfterMidnight에서 전날 일정으로 판단합니다.
        return !currentTime.isBefore(openTime);
    }

    private boolean isOpenAfterMidnight(
            DailyOperatingHours operatingHours,
            LocalTime currentTime
    ) {
        if (operatingHours == null) {
            return false;
        }

        LocalTime openTime = operatingHours.openTime();
        LocalTime closeTime = operatingHours.closeTime();

        // 시작 시각이 종료 시각보다 늦은 일정만 자정을 넘는 영업입니다.
        // 종료 시각 정각부터는 영업이 끝난 것으로 처리합니다.
        return openTime.isAfter(closeTime)
                && currentTime.isBefore(closeTime);
    }
}
