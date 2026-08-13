package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 슬롯 반환 처리에서 사용할 FIFO 승급 제안 전이만 담당한다.
 *
 * <p>이 단계는 대기자 상태를 {@code WAITING -> OFFERED}로 바꾸는 도메인 기반을 만든다. 슬롯을
 * OPEN으로 반환하거나 기존 취소·거절 흐름에 연결하는 일은 다음 단계의 단일 트랜잭션 오케스트레이션에서
 * 처리한다.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationWaitlistPromotionService {

    private final ReservationWaitlistRepository reservationWaitlistRepository;
    private final ReservationWaitlistProperties properties;
    private final Clock clock;

    @Transactional
    public Optional<ReservationWaitlist> offerFirstWaiting(Long slotId) {
        return reservationWaitlistRepository
                .findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                        slotId,
                        ReservationWaitlistStatus.WAITING
                )
                .stream()
                .findFirst()
                .map(waitlist -> {
                    LocalDateTime offeredAt = LocalDateTime.now(clock);
                    waitlist.offer(
                            offeredAt,
                            offeredAt.plusMinutes(properties.getOfferValidityMinutes())
                    );
                    // 슬롯 반환을 동시에 처리한 두 트랜잭션이 같은 FIFO 대기자를 읽어도,
                    // @Version 충돌을 이 경계에서 즉시 감지해 한 쪽만 후속 처리를 계속한다.
                    reservationWaitlistRepository.flush();
                    return waitlist;
                });
    }
}
