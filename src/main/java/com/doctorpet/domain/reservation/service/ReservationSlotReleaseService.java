package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.doctorpet.domain.reservation.policy.ReservationPolicy.LEAD_TIME;

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
    private final ReservationWaitlistRepository reservationWaitlistRepository;
    private final ReservationWaitlistPromotionService promotionService;
    private final Clock clock;

    @Transactional
    public void release(Long slotId) {
        boolean offered = promotionService.offerFirstWaiting(slotId).isPresent();
        if (offered) {
            return;
        }

        if (reservationSlotRepository.openIfNoActiveWaitlist(slotId) == 1) {
            return;
        }

        // 대기열 등록이 후보 조회와 OPEN 조건부 UPDATE 사이에 커밋됐을 수 있다. 다시 FIFO 승급을
        // 시도하면 새 WAITING을 OFFERED로 전이하고, 이미 다른 반환 처리에서 승급된 경우에는 no-op이다.
        if (promotionService.offerFirstWaiting(slotId).isPresent()) {
            return;
        }
        if (reservationWaitlistRepository.existsBySlotIdAndStatus(
                slotId, ReservationWaitlistStatus.OFFERED)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        // 보호자 예약 취소는 시작 2시간 전까지 허용되지만, 승급은 4시간 전까지만 가능하다. 이 구간의
        // WAITING을 그대로 두면 슬롯을 OPEN으로 반환할 수 없으므로 시스템 취소 후 반환한다.
        int canceledWaiting = reservationWaitlistRepository.cancelWaitingIfPromotionDeadlinePassed(
                slotId,
                now.plus(LEAD_TIME),
                now
        );
        if (canceledWaiting > 0
                && reservationSlotRepository.openIfNoActiveWaitlist(slotId) == 1) {
            return;
        }

        // 반환 대상이 이미 OPEN이면 현재 호출은 슬롯 반환을 성립시키지 못한 것이다. 예약 상태만
        // 취소된 채 커밋되지 않도록 예외를 전파해 상위 취소·거절 트랜잭션을 함께 롤백한다.
        throw new ServiceException(SlotErrorCode.INVALID_STATUS);
    }

    /**
     * 휴업·폐업 등 병원 운영 상태 변경으로 예약을 취소할 때의 반환 경로다. 이 경우에는 병원이 새
     * 예약을 받을 수 없으므로 FIFO 승급을 만들지 않고 WAITING·OFFERED를 모두 시스템 취소한 뒤
     * 슬롯을 OPEN으로 반환한다.
     */
    @Transactional
    public void releaseForBusinessStatusChange(Long slotId) {
        LocalDateTime now = LocalDateTime.now(clock);
        reservationWaitlistRepository.cancelActiveForBusinessStatusChange(slotId, now);
        if (reservationSlotRepository.openIfNoActiveWaitlist(slotId) == 1) {
            return;
        }
        throw new ServiceException(SlotErrorCode.INVALID_STATUS);
    }
}
