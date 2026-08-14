package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.dto.response.ReservationWaitlistResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.member.service.MemberService;
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
import java.util.List;

import static com.doctorpet.domain.reservation.policy.ReservationPolicy.LEAD_TIME;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationWaitlistService {

    private final ReservationWaitlistRepository reservationWaitlistRepository;
    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationApplicationService reservationApplicationService;
    private final ReservationSlotReleaseService reservationSlotReleaseService;
    private final MemberService memberService;
    private final Clock clock;

    @Transactional
    public ReservationWaitlistResponse register(Long memberId, Long slotId) {
        memberService.assertActiveMember(memberId);
        ReservationSlot slot = reservationSlotRepository.findById(slotId)
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (slot.getStatus() != ReservationSlotStatus.RESERVED) {
            throw new ServiceException(ReservationWaitlistErrorCode.SLOT_NOT_RESERVED);
        }
        validateLeadTime(slot, LocalDateTime.now(clock));
        if (reservationWaitlistRepository.reactivateTerminalIfSlotReserved(
                memberId,
                slotId,
                LocalDateTime.now(clock)
        ) == 1) {
            return findWaitlistResponse(memberId, slotId);
        }
        if (reservationWaitlistRepository.existsByMemberIdAndSlotId(memberId, slotId)) {
            throw new ServiceException(ReservationWaitlistErrorCode.ALREADY_REGISTERED);
        }

        int inserted = reservationWaitlistRepository.insertWaitingIfSlotReserved(memberId, slotId);
        if (inserted == 0) {
            if (!reservationWaitlistRepository.existsByMemberIdAndSlotId(memberId, slotId)) {
                throw new ServiceException(ReservationWaitlistErrorCode.SLOT_NOT_RESERVED);
            }
            throw new ServiceException(ReservationWaitlistErrorCode.ALREADY_REGISTERED);
        }
        return findWaitlistResponse(memberId, slotId);
    }

    private ReservationWaitlistResponse findWaitlistResponse(Long memberId, Long slotId) {
        ReservationWaitlist waitlist = reservationWaitlistRepository
                .findByMemberIdAndSlotId(memberId, slotId)
                .orElseThrow(() -> new IllegalStateException("저장한 예약 대기열을 찾을 수 없습니다."));
        return ReservationWaitlistResponse.from(waitlist);
    }

    public List<ReservationWaitlistResponse> getMyWaitlists(Long memberId) {
        return reservationWaitlistRepository.findAllByMemberIdOrderByCreatedAtDescIdDesc(memberId)
                .stream()
                .map(ReservationWaitlistResponse::from)
                .toList();
    }

    public ReservationWaitlistResponse getMyWaitlist(Long memberId, Long waitlistId) {
        return ReservationWaitlistResponse.from(findOwned(waitlistId, memberId));
    }

    @Transactional
    public void cancel(Long memberId, Long waitlistId) {
        ReservationWaitlist waitlist = findOwned(waitlistId, memberId);
        if (waitlist.getStatus() != com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.WAITING) {
            throw new ServiceException(ReservationWaitlistErrorCode.CANCELLATION_NOT_ALLOWED);
        }
        waitlist.cancel(LocalDateTime.now(clock));
    }

    /**
     * 회원 탈퇴 확정 전 남은 대기열을 종료한다. OFFERED는 슬롯을 실제로 점유하고 있으므로 상태를
     * 먼저 flush한 뒤 반환·다음 승급을 같은 트랜잭션에서 이어간다.
     */
    @Transactional
    public void cancelAllForWithdrawal(Long memberId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationWaitlist> waitlists = reservationWaitlistRepository.findByMemberIdAndStatusIn(
                memberId,
                List.of(
                        com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.WAITING,
                        com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.OFFERED
                )
        );
        for (ReservationWaitlist waitlist : waitlists) {
            boolean offered = waitlist.getStatus()
                    == com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.OFFERED;
            waitlist.cancelForWithdrawal(now);
            reservationWaitlistRepository.flush();
            if (offered) {
                reservationSlotReleaseService.release(waitlist.getSlotId());
            }
        }
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
        ReservationSlot slot = reservationSlotRepository.findById(waitlist.getSlotId())
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));
        validateLeadTime(slot, now);

        // OFFERED && offer_expires_at > now 조건을 DB에서 원자적으로 선점한다. 만료 배치나
        // 중복 수락 요청과 경합해도 1건만 ACCEPTED가 되고, 아래 REQUESTED 생성은 성공한 트랜잭션에만 묶인다.
        if (reservationWaitlistRepository.acceptIfActive(waitlistId, memberId, now) == 0) {
            throw new ServiceException(ReservationWaitlistErrorCode.OFFER_NOT_ACTIVE);
        }
        ReservationResponse reservation = reservationApplicationService.requestFromWaitlist(
                memberId, petId, paymentMethodId, waitlist.getSlotId());
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

    private void validateLeadTime(ReservationSlot slot, LocalDateTime now) {
        if (slot.getStartAt().isBefore(now.plus(LEAD_TIME))) {
            throw new ServiceException(com.doctorpet.domain.reservation.exception.ReservationErrorCode.LEAD_TIME_VIOLATION);
        }
    }
}
