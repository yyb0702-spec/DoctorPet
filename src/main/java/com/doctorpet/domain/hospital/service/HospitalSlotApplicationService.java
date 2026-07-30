package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDateAvailabilityResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotAvailabilityStatus;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotLookupResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotResponse;
import com.doctorpet.domain.reservation.dto.query.ReservationSlotQueryResult;
import com.doctorpet.domain.reservation.service.ReservationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HospitalSlotApplicationService {

    private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final int LOOKUP_DATE_RANGE_DAYS = 14;
    private static final int RESERVATION_LEAD_TIME_HOURS = 4;

    private final HospitalService hospitalService;
    private final ReservationService reservationService;

    public HospitalSlotLookupResponse getHospitalSlots(
            Long hospitalId,
            LocalDate selectedDate
    ) {
        if (!hospitalService.isReservationSlotLookupAvailable(hospitalId)) {
            return HospitalSlotLookupResponse.empty(selectedDate);
        }

        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        LocalDate today = now.toLocalDate();
        LocalDate lastLookupDate = today.plusDays(
                LOOKUP_DATE_RANGE_DAYS - 1L
        );
        LocalDateTime reservationDeadline = now.plusHours(
                RESERVATION_LEAD_TIME_HOURS
        );
        List<ReservationSlotQueryResult> queriedSlots =
                reservationService.findSlots(
                        hospitalId,
                        today.atStartOfDay(),
                        today.plusDays(LOOKUP_DATE_RANGE_DAYS).atStartOfDay()
                );

        List<HospitalDateAvailabilityResponse> dateAvailabilities =
                today.datesUntil(today.plusDays(LOOKUP_DATE_RANGE_DAYS))
                        .map(date -> new HospitalDateAvailabilityResponse(
                                date,
                                hasAvailableSlot(
                                        queriedSlots,
                                        date,
                                        reservationDeadline
                                )
                        ))
                        .toList();

        List<HospitalSlotResponse> slots =
                selectedDate.isBefore(today)
                        || selectedDate.isAfter(lastLookupDate)
                        ? List.of()
                        : queriedSlots.stream()
                                .filter(slot -> slot.startAt()
                                        .toLocalDate()
                                        .equals(selectedDate))
                                .map(slot -> HospitalSlotResponse.from(
                                        slot,
                                        reservationDeadline
                                ))
                                .toList();

        return new HospitalSlotLookupResponse(
                selectedDate,
                dateAvailabilities,
                slots
        );
    }

    private boolean hasAvailableSlot(
            List<ReservationSlotQueryResult> slots,
            LocalDate date,
            LocalDateTime reservationDeadline
    ) {
        return slots.stream()
                .filter(slot -> slot.startAt().toLocalDate().equals(date))
                .anyMatch(slot ->
                        HospitalSlotAvailabilityStatus.resolve(
                                slot.status(),
                                slot.startAt(),
                                reservationDeadline
                        ) == HospitalSlotAvailabilityStatus.AVAILABLE
                );
    }
}
