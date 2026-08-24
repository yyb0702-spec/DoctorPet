package com.doctorpet.domain.hospital.dto.response;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.dto.query.HospitalResponseMetrics;
import com.doctorpet.domain.review.dto.response.ReviewRatingSummary;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public record HospitalDetailResponse(
        Long hospitalId,
        String name,
        String address,
        String phoneNumber,
        BusinessStatus businessStatus,
        PartnershipStatus partnershipStatus,
        String partnershipNotice,
        Boolean openNow,
        Boolean surgeryAvailable,
        Boolean hospitalizationAvailable,
        Boolean nightCare,
        Boolean emergency,
        List<HospitalBusinessHourResponse> businessHours,
        List<CapabilityValue> capabilities,
        BigDecimal averageRating,
        long reviewCount,
        boolean favorite,
        Integer reservationResponseRate,
        Integer averageApprovalMinutes
) {

    private static final String NON_PARTNER_NOTICE =
            "제휴 전 병원입니다. 운영시간과 진료 가능 항목은 병원에 직접 확인해 주세요.";

    public static HospitalDetailResponse from(
            Hospital hospital,
            HospitalDetail detail,
            List<HospitalCapability> hospitalCapabilities,
            Boolean openNow,
            ReviewRatingSummary ratingSummary,
            HospitalResponseMetrics responseMetrics
    ) {
        boolean partner = hospital.getPartnershipStatus()
                == PartnershipStatus.PARTNER;

        return new HospitalDetailResponse(
                hospital.getId(),
                hospital.getName(),
                resolveAddress(hospital),
                hospital.getPhone(),
                hospital.getBusinessStatus(),
                hospital.getPartnershipStatus(),
                partner ? null : NON_PARTNER_NOTICE,
                partner ? openNow : null,
                partner ? detail.isSurgeryAvailable() : null,
                partner ? detail.isHospitalizationAvailable() : null,
                partner ? detail.isNightCare() : null,
                partner ? detail.isEmergency() : null,
                partner ? toBusinessHours(detail) : null,
                partner ? toCapabilities(hospitalCapabilities) : null,
                ratingSummary.averageRating(),
                ratingSummary.reviewCount(),
                false,
                partner ? responseMetrics.reservationResponseRate() : null,
                partner ? responseMetrics.averageApprovalMinutes() : null
        );
    }

    public HospitalDetailResponse withFavorite(boolean favorite) {
        return new HospitalDetailResponse(
                hospitalId,
                name,
                address,
                phoneNumber,
                businessStatus,
                partnershipStatus,
                partnershipNotice,
                openNow,
                surgeryAvailable,
                hospitalizationAvailable,
                nightCare,
                emergency,
                businessHours,
                capabilities,
                averageRating,
                reviewCount,
                favorite,
                reservationResponseRate,
                averageApprovalMinutes
        );
    }

    public HospitalDetailResponse withResponseMetrics(
            HospitalResponseMetrics responseMetrics
    ) {
        if (partnershipStatus != PartnershipStatus.PARTNER) {
            return this;
        }
        return new HospitalDetailResponse(
                hospitalId,
                name,
                address,
                phoneNumber,
                businessStatus,
                partnershipStatus,
                partnershipNotice,
                openNow,
                surgeryAvailable,
                hospitalizationAvailable,
                nightCare,
                emergency,
                businessHours,
                capabilities,
                averageRating,
                reviewCount,
                favorite,
                responseMetrics.reservationResponseRate(),
                responseMetrics.averageApprovalMinutes()
        );
    }

    private static String resolveAddress(Hospital hospital) {
        if (hospital.getAddressRoad() != null
                && !hospital.getAddressRoad().isBlank()) {
            return hospital.getAddressRoad();
        }
        return hospital.getAddressJibun();
    }

    private static List<HospitalBusinessHourResponse> toBusinessHours(
            HospitalDetail detail
    ) {
        if (detail == null || detail.getOpenHours() == null) {
            return null;
        }

        return Arrays.stream(DayOfWeek.values())
                .map(dayOfWeek -> HospitalBusinessHourResponse.from(
                        dayOfWeek,
                        detail.getOpenHours().get(dayOfWeek)
                ))
                .toList();
    }

    private static List<CapabilityValue> toCapabilities(
            List<HospitalCapability> hospitalCapabilities
    ) {
        if (hospitalCapabilities == null) {
            return null;
        }

        return hospitalCapabilities.stream()
                .map(HospitalCapability::getCapabilityValue)
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
