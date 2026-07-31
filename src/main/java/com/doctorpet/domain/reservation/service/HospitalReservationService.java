package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalReservationService {

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

        LocalDateTime now = LocalDateTime.now();
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
            String rejectReason
    ) {
        if (rejectReason == null || rejectReason.isBlank()) {
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
                rejectReason,
                now
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }

        ReservationSlot slot = reservationSlotRepository.findById(
                reservation.getSlotId()
        ).orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));
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

        int updated = reservationRepository.checkInIfConfirmed(
                reservationId,
                hospitalId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CHECKED_IN,
                LocalDateTime.now()
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

    private void assertHospitalOwnership(
            Reservation reservation,
            Long hospitalId
    ) {
        if (!hospitalId.equals(reservation.getHospitalId())) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
    }
}
