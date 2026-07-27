package com.doctorpet.domain.reservation.entity;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.global.entity.BaseEntity;
import com.doctorpet.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(name = "payment_method_id", nullable = false)
    private Long paymentMethodId;

    @Column(name = "pet_name_snapshot", nullable = false, length = 50)
    private String petNameSnapshot;

    @Column(name = "pet_species_snapshot", nullable = false, length = 10)
    private String petSpeciesSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationStatus status;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "no_show_at")
    private LocalDateTime noShowAt;

    private Reservation(
            Long memberId,
            Long petId,
            Long hospitalId,
            Long slotId,
            Long paymentMethodId,
            String petNameSnapshot,
            String petSpeciesSnapshot,
            LocalDateTime requestedAt
    ) {
        this.memberId = memberId;
        this.petId = petId;
        this.hospitalId = hospitalId;
        this.slotId = slotId;
        this.paymentMethodId = paymentMethodId;
        this.petNameSnapshot = petNameSnapshot;
        this.petSpeciesSnapshot = petSpeciesSnapshot;
        this.status = ReservationStatus.REQUESTED;
        this.requestedAt = requestedAt;
    }

    public static Reservation request(
            Long memberId,
            Long petId,
            Long hospitalId,
            Long slotId,
            Long paymentMethodId,
            String petNameSnapshot,
            String petSpeciesSnapshot,
            LocalDateTime requestedAt
    ) {
        return new Reservation(
                memberId,
                petId,
                hospitalId,
                slotId,
                paymentMethodId,
                petNameSnapshot,
                petSpeciesSnapshot,
                requestedAt
        );
    }

    public void confirm(LocalDateTime now) {
        validateStatus(ReservationStatus.REQUESTED);

        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = now;
    }

    public void reject(String reason) {
        validateStatus(ReservationStatus.REQUESTED);

        if (reason == null || reason.isBlank()) {
            throw new ServiceException(ReservationErrorCode.REJECT_REASON_REQUIRED);
        }

        this.status = ReservationStatus.REJECTED;
        this.rejectReason = reason;
    }

    public void cancel(LocalDateTime now) {
        if (status != ReservationStatus.REQUESTED
                && status != ReservationStatus.CONFIRMED) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }

        this.status = ReservationStatus.CANCELED;
        this.canceledAt = now;
    }

    public void checkIn() {
        validateStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.CHECKED_IN;
    }

    public void startTreatment() {
        validateStatus(ReservationStatus.CHECKED_IN);
        this.status = ReservationStatus.IN_TREATMENT;
    }

    public void completeTreatment() {
        validateStatus(ReservationStatus.IN_TREATMENT);
        this.status = ReservationStatus.TREATMENT_COMPLETED;
    }

    public void markNoShow(LocalDateTime now) {
        validateStatus(ReservationStatus.CONFIRMED);
        this.status = ReservationStatus.NO_SHOW;
        this.noShowAt = now;
    }

    public void restoreNoShow() {
        validateStatus(ReservationStatus.NO_SHOW);
        this.status = ReservationStatus.CHECKED_IN;
    }

    public boolean isOwnedBy(Long memberId) {
        return this.memberId.equals(memberId);
    }

    private void validateStatus(ReservationStatus expectedStatus) {
        if (this.status != expectedStatus) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }
    }
}
