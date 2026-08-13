package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.dto.response.ReservationWaitlistResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
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
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationWaitlistService {

    private final ReservationWaitlistRepository reservationWaitlistRepository;
    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationApplicationService reservationApplicationService;
    private final ReservationSlotReleaseService reservationSlotReleaseService;
    private final Clock clock;

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

    @Transactional
    public ReservationResponse accept(
            Long memberId,
            Long waitlistId,
            Long petId,
            Long paymentMethodId
    ) {
        ReservationWaitlist waitlist = findOwned(waitlistId, memberId);
        LocalDateTime now = LocalDateTime.now(clock);
        validateActiveOffer(waitlist, now);
        ReservationResponse reservation = reservationApplicationService.requestFromWaitlist(
                memberId, petId, paymentMethodId, waitlist.getSlotId());
        waitlist.accept(now);
        reservationWaitlistRepository.flush();
        return reservation;
    }

    @Transactional
    public void reject(Long memberId, Long waitlistId) {
        ReservationWaitlist waitlist = findOwned(waitlistId, memberId);
        LocalDateTime now = LocalDateTime.now(clock);
        validateActiveOffer(waitlist, now);
        waitlist.reject(now);
        reservationWaitlistRepository.flush();
        reservationSlotReleaseService.release(waitlist.getSlotId());
    }

    @Transactional
    public boolean expire(Long waitlistId, LocalDateTime now) {
        ReservationWaitlist waitlist = reservationWaitlistRepository.findById(waitlistId)
                .orElse(null);
        if (waitlist == null || !waitlist.isOfferExpiredAt(now)) {
            return false;
        }
        waitlist.expire(now);
        reservationWaitlistRepository.flush();
        reservationSlotReleaseService.release(waitlist.getSlotId());
        return true;
    }

    private ReservationWaitlist findOwned(Long waitlistId, Long memberId) {
        ReservationWaitlist waitlist = reservationWaitlistRepository.findById(waitlistId)
                .orElseThrow(() -> new ServiceException(
                        ReservationWaitlistErrorCode.WAITLIST_NOT_FOUND));
        if (!waitlist.getMemberId().equals(memberId)) {
            throw new ServiceException(com.doctorpet.global.exception.CommonErrorCode.FORBIDDEN);
        }
        return waitlist;
    }

    private void validateActiveOffer(ReservationWaitlist waitlist, LocalDateTime now) {
        if (waitlist.getStatus() != com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.OFFERED) {
            throw new ServiceException(ReservationWaitlistErrorCode.OFFER_NOT_ACTIVE);
        }
        if (waitlist.isOfferExpiredAt(now)) {
            throw new ServiceException(ReservationWaitlistErrorCode.OFFER_EXPIRED);
        }
    }
}
