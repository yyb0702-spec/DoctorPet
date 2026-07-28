package com.doctorpet.domain.reservation.lock;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OptimisticReservationLockStrategy implements ReservationLockStrategy {

    private final ReservationSlotRepository reservationSlotRepository;

    @Override
    public ReservationSlot reserve(Long slotId) {
        ReservationSlot slot = reservationSlotRepository.findById(slotId)
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        try {
            slot.reserve();
            // 커밋 시점까지 미루지 않고 이 경계에서 충돌을 감지해 도메인 오류로 변환한다.
            reservationSlotRepository.flush();
            return slot;
        } catch (OptimisticLockingFailureException exception) {
            throw new ServiceException(SlotErrorCode.ALREADY_RESERVED);
        }
    }
}
