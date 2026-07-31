package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.dto.query.ReservationHistoryAggregate;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.dto.response.HospitalReservationListItemResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationHistoryResponse;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalReservationApplicationService {

    private final MemberService memberService;
    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository reservationSlotRepository;

    /**
     * 병원 스태프가 자기 병원의 REQUESTED 예약을 승인한다(SA §5-1, §6-2).
     */
    @Transactional
    public void approve(Long staffMemberId, Long reservationId) {
        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        ReservationSlot slot = findSlot(reservation.getSlotId());
        LocalDateTime now = LocalDateTime.now();
        validateApprovalDeadline(reservation, slot, now);
        int updated = reservationRepository.approveIfRequested(
                reservationId,
                hospitalId,
                ReservationStatus.REQUESTED,
                ReservationStatus.CONFIRMED,
                now,
                now
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
    }

    /**
     * 병원 스태프가 자기 병원의 REQUESTED 예약을 거절하고 슬롯을 반환한다(SA §5-1·§5-3).
     */
    @Transactional
    public void reject(
            Long staffMemberId,
            Long reservationId,
            ReservationRejectReason rejectReason
    ) {
        if (rejectReason == null) {
            throw new ServiceException(ReservationErrorCode.REJECT_REASON_REQUIRED);
        }

        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        LocalDateTime now = LocalDateTime.now();
        int updated = reservationRepository.rejectIfRequested(
                reservationId,
                hospitalId,
                ReservationStatus.REQUESTED,
                ReservationStatus.REJECTED,
                rejectReason.value(),
                now
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }

        ReservationSlot slot = findSlot(reservation.getSlotId());
        slot.open();
    }

    /**
     * 병원 스태프가 자기 병원의 CONFIRMED 예약을 체크인 처리한다(SA §5-1·§8-6).
     */
    @Transactional
    public void checkIn(Long staffMemberId, Long reservationId) {
        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        ReservationSlot slot = findSlot(reservation.getSlotId());
        LocalDateTime now = LocalDateTime.now();
        validateCheckInDeadline(slot, now);
        int updated = reservationRepository.checkInIfConfirmed(
                reservationId,
                hospitalId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CHECKED_IN,
                now
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
    }

    /**
     * 병원 스태프가 체크인된 예약의 진료를 시작한다(SA §5-1·§8-6).
     */
    @Transactional
    public void startTreatment(Long staffMemberId, Long reservationId) {
        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        int updated = reservationRepository.startTreatmentIfCheckedIn(
                reservationId,
                hospitalId,
                ReservationStatus.CHECKED_IN,
                ReservationStatus.IN_TREATMENT,
                LocalDateTime.now()
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
    }

    /**
     * 병원 스태프가 진료 중인 예약을 진료 완료 처리한다(SA §5-1·§8-6).
     */
    @Transactional
    public void completeTreatment(Long staffMemberId, Long reservationId) {
        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        int updated = reservationRepository.completeTreatmentIfInTreatment(
                reservationId,
                hospitalId,
                ReservationStatus.IN_TREATMENT,
                ReservationStatus.TREATMENT_COMPLETED,
                LocalDateTime.now()
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
    }

    public Page<HospitalReservationListItemResponse> findHospitalReservations(
            Long staffMemberId,
            String status,
            int page,
            int size
    ) {
        Long hospitalId = requireHospitalId(staffMemberId);
        ReservationStatus reservationStatus = parseStatus(status);
        Page<Reservation> reservations = reservationRepository.findByHospitalIdAndStatus(
                hospitalId,
                reservationStatus,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "requestedAt"))
        );

        Map<Long, ReservationSlot> slotsById = findSlotsById(reservations.getContent());
        Map<Long, ReservationHistoryResponse> historiesByMemberId =
                findHistoriesByMemberId(reservations.getContent());

        return reservations.map(reservation -> {
            ReservationSlot slot = requireSlot(slotsById, reservation.getSlotId());
            ReservationHistoryResponse history = historiesByMemberId.getOrDefault(
                    reservation.getMemberId(),
                    new ReservationHistoryResponse(0L, 0L, 0L, 0L)
            );
            return HospitalReservationListItemResponse.from(
                    reservation,
                    slot.getStartAt(),
                    history
            );
        });
    }

    private ReservationStatus parseStatus(String status) {
        try {
            return ReservationStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ServiceException(ReservationErrorCode.INVALID_FILTER_STATUS);
        }
    }

    private Map<Long, ReservationSlot> findSlotsById(
            Collection<Reservation> reservations
    ) {
        if (reservations.isEmpty()) {
            return Collections.emptyMap();
        }

        Collection<Long> slotIds = reservations.stream()
                .map(Reservation::getSlotId)
                .distinct()
                .toList();
        return reservationSlotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(
                        ReservationSlot::getId,
                        Function.identity()
                ));
    }

    private Map<Long, ReservationHistoryResponse> findHistoriesByMemberId(
            Collection<Reservation> reservations
    ) {
        if (reservations.isEmpty()) {
            return Collections.emptyMap();
        }

        Collection<Long> memberIds = reservations.stream()
                .map(Reservation::getMemberId)
                .distinct()
                .toList();
        return reservationRepository.findHistoryAggregates(
                        memberIds,
                        ReservationStatus.TREATMENT_COMPLETED,
                        ReservationStatus.CANCELED,
                        ReservationStatus.NO_SHOW
                ).stream()
                .collect(Collectors.toMap(
                        ReservationHistoryAggregate::memberId,
                        ReservationHistoryAggregate::toResponse
                ));
    }

    private ReservationSlot requireSlot(
            Map<Long, ReservationSlot> slotsById,
            Long slotId
    ) {
        ReservationSlot slot = slotsById.get(slotId);
        if (slot == null) {
            throw new ServiceException(SlotErrorCode.SLOT_NOT_FOUND);
        }
        return slot;
    }

    private Long requireHospitalId(Long staffMemberId) {
        MemberResponse member = memberService.getMyInfo(staffMemberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF
                || member.hospitalId() == null) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        return member.hospitalId();
    }

    private Reservation findReservation(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));
    }

    private ReservationSlot findSlot(Long slotId) {
        return reservationSlotRepository.findById(slotId)
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));
    }

    private void validateApprovalDeadline(
            Reservation reservation,
            ReservationSlot slot,
            LocalDateTime now
    ) {
        LocalDateTime requestDeadline = reservation.getRequestedAt().plusHours(1);
        LocalDateTime slotDeadline = slot.getStartAt().minusHours(2);
        LocalDateTime approvalDeadline = requestDeadline.isBefore(slotDeadline)
                ? requestDeadline
                : slotDeadline;

        if (now.isAfter(approvalDeadline)) {
            throw new ServiceException(
                    ReservationErrorCode.APPROVAL_DEADLINE_PASSED
            );
        }
    }

    private void validateCheckInDeadline(
            ReservationSlot slot,
            LocalDateTime now
    ) {
        if (now.isAfter(slot.getStartAt().plusMinutes(10))) {
            throw new ServiceException(
                    ReservationErrorCode.CHECK_IN_DEADLINE_PASSED
            );
        }
    }

    private void assertHospitalOwnership(
            Reservation reservation,
            Long hospitalId
    ) {
        if (!hospitalId.equals(reservation.getHospitalId())) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
    }
}
