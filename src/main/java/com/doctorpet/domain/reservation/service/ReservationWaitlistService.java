package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.dto.response.ReservationWaitlistResponse;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.exception.ReservationWaitlistErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationWaitlistService {

    private final ReservationWaitlistRepository reservationWaitlistRepository;
    private final ReservationSlotRepository reservationSlotRepository;

    @Transactional
    public ReservationWaitlistResponse register(Long memberId, Long slotId) {
        ReservationSlot slot = reservationSlotRepository.findById(slotId)
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (slot.getStatus() != ReservationSlotStatus.RESERVED) {
            throw new ServiceException(ReservationWaitlistErrorCode.SLOT_NOT_RESERVED);
        }
        if (reservationWaitlistRepository.existsByMemberIdAndSlotId(memberId, slotId)) {
            throw new ServiceException(ReservationWaitlistErrorCode.ALREADY_REGISTERED);
        }

        int inserted = reservationWaitlistRepository.insertWaitingIfAbsent(memberId, slotId);
        if (inserted == 0) {
            throw new ServiceException(ReservationWaitlistErrorCode.ALREADY_REGISTERED);
        }
        ReservationWaitlist waitlist = reservationWaitlistRepository
                .findByMemberIdAndSlotId(memberId, slotId)
                .orElseThrow(() -> new IllegalStateException("저장한 예약 대기열을 찾을 수 없습니다."));
        return ReservationWaitlistResponse.from(waitlist);
    }
}
