package com.doctorpet.domain.reservation.entity;

import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservation_waitlists",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_reservation_waitlists_member_slot",
                        columnNames = {"member_id", "slot_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_reservation_waitlists_slot_status_created",
                        columnList = "slot_id, status, created_at, id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationWaitlist extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationWaitlistStatus status;

    @Column(name = "offered_at")
    private LocalDateTime offeredAt;

    @Column(name = "offer_expires_at")
    private LocalDateTime offerExpiresAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Version
    private Long version;

    private ReservationWaitlist(Long memberId, Long slotId) {
        this.memberId = memberId;
        this.slotId = slotId;
        this.status = ReservationWaitlistStatus.WAITING;
    }

    public static ReservationWaitlist waiting(Long memberId, Long slotId) {
        return new ReservationWaitlist(memberId, slotId);
    }
}
