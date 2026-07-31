package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
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
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.doctorpet.domain.reservation.policy.ReservationPolicy.LEAD_TIME;
import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationLockStrategy reservationLockStrategy;

    /*
      회원 탈퇴 전 활성 예약(CONFIRMED·CHECKED_IN) 보유 여부 확인용(SA §6-3, 부록A 확정).
      다른 도메인(Member)은 이 Service를 경유해서만 호출한다 — ReservationRepository를
      직접 참조하지 않는다(구현 가드레일).
     */
    public boolean hasActiveReservation(Long memberId) {
        return reservationRepository.existsByMemberIdAndStatusIn(
                memberId,
                List.of(ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)
        );
    }

    /**
     * 예약 생성에 필요한 슬롯 점유·리드타임·예약 저장만 담당한다.
     * 회원·반려동물·결제수단 검증과 스냅샷 생성은 ReservationApplicationService가 수행한다.
     */
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
    public ReservationResponse request(
            Long memberId,
            ReservationRequest request,
            String petNameSnapshot,
            String petSpeciesSnapshot
    ) {
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        ReservationSlot slot = findSlot(request.slotId());
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
                petNameSnapshot,
                petSpeciesSnapshot,
                now
        );

        return ReservationResponse.from(reservationRepository.save(reservation));
    }

    @Transactional
    public void cancel(Long memberId, Long reservationId) {
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        // 상세 조회와 동일하게, 존재하는 타인 예약은 FORBIDDEN으로 구분한다.
        Reservation reservation = findMyReservation(memberId, reservationId);

        ReservationSlot slot = findSlot(reservation.getSlotId());
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

    public Reservation findMyReservation(Long memberId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));
        if (!reservation.isOwnedBy(memberId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }
        return reservation;
    }

    public Page<Reservation> findMyReservations(
            Long memberId,
            ReservationListCondition condition
    ) {
        validatePage(condition);
        validateDateRange(condition.from(), condition.to());

        ReservationStatus status = parseStatus(condition.status());
        Sort.Direction direction = parseSort(condition.sort());
        LocalDateTime fromAt = startOfDay(condition.from());
        LocalDateTime toExclusive = startOfNextDay(condition.to());
        PageRequest pageRequest = PageRequest.of(
                condition.page(),
                condition.size()
        );

        return reservationRepository.findMyReservations(
                memberId,
                status,
                fromAt,
                toExclusive,
                direction,
                pageRequest
        );
    }

    public ReservationSlot findSlot(Long slotId) {
        return reservationSlotRepository.findById(slotId)
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));
    }

    public List<ReservationSlot> findSlots(Collection<Long> slotIds) {
        return reservationSlotRepository.findAllById(slotIds);
    }

    private void validatePage(ReservationListCondition condition) {
        if (condition.page() < 0
                || condition.size() < 1
                || condition.size() > MAX_PAGE_SIZE) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ServiceException(ReservationErrorCode.INVALID_DATE_RANGE);
        }
    }

    private ReservationStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }

        try {
            return ReservationStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(ReservationErrorCode.INVALID_FILTER_STATUS);
        }
    }

    private Sort.Direction parseSort(String sort) {
        // reservedAt은 예약 요청 시각(requestedAt)이 아니라 진료 예약 슬롯의 startAt을 의미한다.
        String resolvedSort = sort == null ? "reservedAt,desc" : sort.trim();
        String[] parts = resolvedSort.split(",", -1);

        if (parts.length != 2 || !"reservedAt".equals(parts[0].trim())) {
            throw new ServiceException(ReservationErrorCode.INVALID_SORT);
        }

        try {
            return Sort.Direction.fromString(parts[1].trim());
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(ReservationErrorCode.INVALID_SORT);
        }
    }

    private LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDateTime startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }
}
