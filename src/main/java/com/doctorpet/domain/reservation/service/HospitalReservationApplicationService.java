package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.dto.query.ReservationHistoryAggregate;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.dto.response.HospitalReservationListItemResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationHistoryResponse;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
import com.doctorpet.domain.reservation.repository.ReservationEventRepository;
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
    private final ReservationEventRepository reservationEventRepository;
    // 상태 전이 트랜잭션 안에서 보호자 알림을 저장한다 — 전이가 롤백되면 알림도 함께 롤백된다(SA §9-8, #88).
    private final ReservationNotificationPublisher notificationPublisher;

    /**
     * 병원 스태프가 자기 병원의 REQUESTED 예약을 승인한다(SA §5-1, §6-2).
     */
    @Transactional
    public void approve(Long staffMemberId, Long reservationId) {
        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        ReservationSlot slot = findSlot(reservation.getSlotId());
        validateApprovableSlot(slot, hospitalId);
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        validateApprovalDeadline(reservation, now);
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

        notificationPublisher.publishConfirmed(reservation.getMemberId(), reservationId);
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

        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
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

        notificationPublisher.publishRejected(reservation.getMemberId(), reservationId);
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
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
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
                LocalDateTime.now(SEOUL_ZONE_ID)
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
                LocalDateTime.now(SEOUL_ZONE_ID)
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
    }

    /**
     * 병원 스태프가 예약 시작 시각 이후 CONFIRMED 예약을 노쇼로 확정한다.
     * 자동 처리가 먼저 완료된 경우에도 수동 처리 이력을 남겨 수동 판단을 우선한다.
     */
    @Transactional
    public void confirmNoShow(
            Long staffMemberId,
            Long reservationId,
            String reason
    ) {
        validateReason(reason, ReservationErrorCode.INVALID_NO_SHOW_REASON);

        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        ReservationSlot slot = findSlot(reservation.getSlotId());
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        if (now.isBefore(slot.getStartAt())) {
            throw new ServiceException(ReservationErrorCode.NO_SHOW_TOO_EARLY);
        }

        int updated = reservationRepository.markNoShowIfConfirmed(
                reservationId,
                hospitalId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
                now,
                now
        );

        if (updated == 0) {
            Reservation current = reservationRepository
                    .findByIdAndHospitalIdForUpdate(reservationId, hospitalId)
                    .orElseThrow(() -> new ServiceException(
                            ReservationErrorCode.RESERVATION_NOT_FOUND
                    ));
            if (current.getStatus() != ReservationStatus.NO_SHOW) {
                throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
            }
        }

        reservationEventRepository.appendIfAbsent(
                reservationId,
                ReservationEventType.MANUAL_NO_SHOW.name(),
                reason,
                staffMemberId,
                now
        );

        // 이 호출이 실제로 CONFIRMED → NO_SHOW 전이를 성립시켰을 때만 알린다. 이미 NO_SHOW였던
        // 멱등 경로(updated == 0)는 앞선 전이에서 이미 발행됐으므로 중복 발행하지 않는다.
        if (updated != 0) {
            notificationPublisher.publishNoShow(reservation.getMemberId(), reservationId);
        }
    }

    /**
     * 병원 스태프가 NO_SHOW 예약을 CHECKED_IN으로 정정하고 새 이력을 추가한다.
     */
    @Transactional
    public void restoreNoShow(
            Long staffMemberId,
            Long reservationId,
            String reason
    ) {
        validateReason(
                reason,
                ReservationErrorCode.INVALID_NO_SHOW_RESTORE_REASON
        );

        Long hospitalId = requireHospitalId(staffMemberId);
        Reservation reservation = findReservation(reservationId);
        assertHospitalOwnership(reservation, hospitalId);

        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        int updated = reservationRepository.restoreNoShowIfNoShow(
                reservationId,
                hospitalId,
                ReservationStatus.NO_SHOW,
                ReservationStatus.CHECKED_IN,
                now
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }

        reservationEventRepository.appendIfAbsent(
                reservationId,
                ReservationEventType.NO_SHOW_CORRECTED.name(),
                reason,
                staffMemberId,
                now
        );
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
            LocalDateTime now
    ) {
        if (!now.isBefore(reservation.getApprovalDeadlineAt())) {
            throw new ServiceException(
                    ReservationErrorCode.APPROVAL_DEADLINE_PASSED
            );
        }
    }

    private void validateApprovableSlot(
            ReservationSlot slot,
            Long hospitalId
    ) {
        if (!hospitalId.equals(slot.getHospitalId())) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        if (slot.getStatus() != ReservationSlotStatus.RESERVED) {
            throw new ServiceException(SlotErrorCode.INVALID_STATUS);
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

    private void validateReason(
            String reason,
            ReservationErrorCode errorCode
    ) {
        if (reason == null || reason.isBlank() || reason.length() > 255) {
            throw new ServiceException(errorCode);
        }
    }
}
