package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.query.ReservationSlotQueryResult;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.lock.ReservationLockStrategy;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.doctorpet.domain.reservation.policy.ReservationPolicy.LEAD_TIME;
import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationLockStrategy reservationLockStrategy;

    public List<ReservationSlotQueryResult> findSlots(
            Long hospitalId,
            LocalDateTime rangeStart,
            LocalDateTime rangeEnd
    ) {
        return reservationSlotRepository
                .findSlotsInRange(
                        hospitalId,
                        rangeStart,
                        rangeEnd
                )
                .stream()
                .map(ReservationSlotQueryResult::from)
                .toList();
    }

    @Transactional
    public ReservationResponse request(Long memberId, ReservationRequest request) {
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        ReservationSlot slot = reservationSlotRepository.findById(request.slotId())
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (slot.getStartAt().isBefore(now.plus(LEAD_TIME))) {
            throw new ServiceException(ReservationErrorCode.LEAD_TIME_VIOLATION);
        }

        slot = reservationLockStrategy.reserve(request.slotId());

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
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        Reservation reservation = reservationRepository
                .findByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));

        ReservationSlot slot = reservationSlotRepository.findById(reservation.getSlotId())
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (now.isAfter(slot.getStartAt().minusHours(2))) {
            throw new ServiceException(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        }

        int updated = reservationRepository.cancelIfAllowed(
                reservationId,
                memberId,
                ReservationStatus.REQUESTED,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELED,
                now
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }

        slot.open();
    }
}
