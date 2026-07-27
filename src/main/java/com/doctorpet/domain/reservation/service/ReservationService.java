package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository reservationSlotRepository;

    @Transactional
    public ReservationResponse request(Long memberId, ReservationRequest request) {
        LocalDateTime now = LocalDateTime.now();

        ReservationSlot slot = reservationSlotRepository.findById(request.slotId())
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (slot.getStartAt().isBefore(now.plusHours(4))) {
            throw new ServiceException(ReservationErrorCode.LEAD_TIME_VIOLATION);
        }

        slot.reserve();

        Reservation reservation = Reservation.request(
                memberId,
                request.petId(),
                slot.getHospitalId(),
                slot.getId(),
                request.paymentMethodId(),
                request.petNameSnapshot(),
                request.petSpeciesSnapshot(),
                now
        );

        return ReservationResponse.from(reservationRepository.save(reservation));
    }

    @Transactional
    public void cancel(Long memberId, Long reservationId) {
        Reservation reservation = reservationRepository
                .findByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));

        ReservationSlot slot = reservationSlotRepository.findById(reservation.getSlotId())
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (LocalDateTime.now().isAfter(slot.getStartAt().minusHours(2))) {
            throw new ServiceException(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        }

        reservation.cancel(LocalDateTime.now());
        slot.open();
    }
}
