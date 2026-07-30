package com.doctorpet.domain.reservation.dto.response;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;

public record ReservationDetailResponse(
        Long reservationId,
        ReservationStatus reservationStatus,
        String paymentStatus,
        ReservationProgressStatus progressStatus,
        HospitalResponse hospital,
        PetSnapshotResponse petSnapshot,
        SlotResponse slot,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ReservationDetailResponse from(
            Reservation reservation,
            ReservationSlot slot,
            HospitalDetailResponse hospital,
            String paymentStatus
    ) {
        return new ReservationDetailResponse(
                reservation.getId(),
                reservation.getStatus(),
                paymentStatus,
                ReservationProgressStatus.from(
                        reservation.getStatus(),
                        paymentStatus
                ),
                new HospitalResponse(
                        hospital.hospitalId(),
                        hospital.name(),
                        hospital.address(),
                        hospital.phoneNumber()
                ),
                new PetSnapshotResponse(
                        reservation.getPetId(),
                        reservation.getPetNameSnapshot(),
                        reservation.getPetSpeciesSnapshot()
                ),
                new SlotResponse(
                        slot.getId(),
                        slot.getStartAt(),
                        slot.getEndAt()
                ),
                reservation.getRejectReason(),
                reservation.getRequestedAt(),
                reservation.getUpdatedAt()
        );
    }

    public record HospitalResponse(
            Long hospitalId,
            String name,
            String address,
            String phoneNumber
    ) {
    }

    public record PetSnapshotResponse(
            Long petId,
            String name,
            String species
    ) {
    }

    public record SlotResponse(
            Long slotId,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
    }
}
