package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.config.ReservationWaitlistProperties;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.doctorpet.domain.reservation.policy.ReservationPolicy.LEAD_TIME;

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
    private final ReservationSlotRepository reservationSlotRepository;
    private final MemberService memberService;
    private final ReservationNotificationPublisher notificationPublisher;
    private final ReservationWaitlistProperties properties;
    private final Clock clock;

    @Transactional
    public Optional<ReservationWaitlist> offerFirstWaiting(Long slotId) {
        ReservationSlot slot = reservationSlotRepository.findById(slotId)
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));
        LocalDateTime offeredAt = LocalDateTime.now(clock);
        LocalDateTime latestAcceptAt = slot.getStartAt().minus(LEAD_TIME);
        // 제안 시각부터 수락 마감까지 유효한 구간이 있어야 한다. 정확히 4시간 전이거나 그 뒤면
        // OFFERED를 만들지 않고 슬롯 반환 서비스가 OPEN으로 되돌린다.
        if (!latestAcceptAt.isAfter(offeredAt)) {
            return Optional.empty();
        }
        for (ReservationWaitlist waitlist : reservationWaitlistRepository
                .findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(slotId, ReservationWaitlistStatus.WAITING)) {
            if (!isActiveMember(waitlist)) {
                waitlist.cancelForWithdrawal(offeredAt);
                reservationWaitlistRepository.flush();
                continue;
            }
            LocalDateTime expiresAt = offeredAt.plusMinutes(properties.getOfferValidityMinutes());
            if (expiresAt.isAfter(latestAcceptAt)) {
                expiresAt = latestAcceptAt;
            }
            waitlist.offer(offeredAt, expiresAt);
            // 슬롯 반환을 동시에 처리한 두 트랜잭션이 같은 FIFO 대기자를 읽어도,
            // @Version 충돌을 이 경계에서 즉시 감지해 한 쪽만 후속 처리를 계속한다.
            reservationWaitlistRepository.flush();
            // OFFERED 전이가 실제로 저장된 경우에만 같은 트랜잭션에 알림을 저장한다.
            // NotificationService의 AFTER_COMMIT 리스너가 SSE 전송을 별도로 수행한다.
            notificationPublisher.publishWaitlistOffered(
                    waitlist.getMemberId(), waitlist.getId(), expiresAt);
            return Optional.of(waitlist);
        }
        return Optional.empty();
    }

    private boolean isActiveMember(ReservationWaitlist waitlist) {
        try {
            memberService.assertActiveMember(waitlist.getMemberId());
            return true;
        } catch (ServiceException exception) {
            if (exception.getErrorCode() == MemberErrorCode.MEMBER_NOT_FOUND) {
                return false;
            }
            throw exception;
        }
    }
}
