package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약이 슬롯을 반환할 때의 단일 진입점이다.
 *
 * <p>대기자가 있으면 첫 대기자를 OFFERED로 바꾸고 슬롯은 RESERVED로 유지한다. 대기자가 없을
 * 때만 OPEN으로 반환한다. 모든 취소·거절·승인 타임아웃 경로가 이 서비스를 사용해야 동일한
 * 정책을 지킨다.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationSlotReleaseService {

    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationWaitlistPromotionService promotionService;

    @Transactional
    public void release(Long slotId) {
        ReservationSlot slot = reservationSlotRepository.findById(slotId)
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        boolean offered = promotionService.offerFirstWaiting(slotId).isPresent();
        if (!offered) {
            slot.open();
        }
    }
}
