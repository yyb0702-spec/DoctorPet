package com.doctorpet.domain.reservation.entity;

import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.global.entity.BaseEntity;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reservations",
        indexes = @Index(
                name = "idx_reservations_status_approval_deadline",
                columnList = "status, approval_deadline_at"
        )
)
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

    @Column(name = "pet_name_snapshot", nullable = false, length = 255)
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

    @Column(name = "approval_deadline_at", nullable = false)
    private LocalDateTime approvalDeadlineAt;

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
            LocalDateTime requestedAt,
            LocalDateTime approvalDeadlineAt
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
        this.approvalDeadlineAt = approvalDeadlineAt;
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
                requestedAt,
                requestedAt.plusHours(1)
        );
    }

    /**
     * 승인 마감 시각을 예약 생성 시 확정한다.
     * 마감은 요청 후 1시간과 예약 시작 2시간 전 중 더 이른 시각이다.
     */
    public static Reservation request(
            Long memberId,
            Long petId,
            Long hospitalId,
            Long slotId,
            Long paymentMethodId,
            String petNameSnapshot,
            String petSpeciesSnapshot,
            LocalDateTime requestedAt,
            LocalDateTime slotStartAt
    ) {
        LocalDateTime requestDeadline = requestedAt.plusHours(1);
        LocalDateTime slotDeadline = slotStartAt.minusHours(2);
        LocalDateTime approvalDeadlineAt = requestDeadline.isBefore(slotDeadline)
                ? requestDeadline
                : slotDeadline;

        return new Reservation(
                memberId,
                petId,
                hospitalId,
                slotId,
                paymentMethodId,
                petNameSnapshot,
                petSpeciesSnapshot,
                requestedAt,
                approvalDeadlineAt
        );
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
