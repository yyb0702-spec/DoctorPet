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

    /** FIFO로 선택된 대기자에게만 예약 기회를 제안한다. */
    public void offer(LocalDateTime offeredAt, LocalDateTime offerExpiresAt) {
        if (status != ReservationWaitlistStatus.WAITING) {
            throw new IllegalStateException("WAITING 상태의 대기열만 승급 제안을 할 수 있습니다.");
        }
        if (!offeredAt.isBefore(offerExpiresAt)) {
            throw new IllegalArgumentException("승급 제안 만료 시각은 제안 시각보다 늦어야 합니다.");
        }

        this.status = ReservationWaitlistStatus.OFFERED;
        this.offeredAt = offeredAt;
        this.offerExpiresAt = offerExpiresAt;
    }

    public void accept(LocalDateTime respondedAt) {
        requireActiveOffer(respondedAt);
        this.status = ReservationWaitlistStatus.ACCEPTED;
        this.respondedAt = respondedAt;
    }

    public void reject(LocalDateTime respondedAt) {
        requireActiveOffer(respondedAt);
        this.status = ReservationWaitlistStatus.REJECTED;
        this.respondedAt = respondedAt;
    }

    public void expire(LocalDateTime expiredAt) {
        if (status != ReservationWaitlistStatus.OFFERED || offerExpiresAt == null
                || expiredAt.isBefore(offerExpiresAt)) {
            throw new IllegalStateException("만료된 OFFERED 대기열만 만료 처리할 수 있습니다.");
        }
        this.status = ReservationWaitlistStatus.EXPIRED;
        this.respondedAt = expiredAt;
    }

    /** 보호자는 아직 승급 제안을 받지 않은 대기만 취소할 수 있다. */
    public void cancel(LocalDateTime canceledAt) {
        if (status != ReservationWaitlistStatus.WAITING) {
            throw new IllegalStateException("WAITING 상태의 대기열만 취소할 수 있습니다.");
        }
        this.status = ReservationWaitlistStatus.CANCELED;
        this.canceledAt = canceledAt;
    }

    public boolean isOfferExpiredAt(LocalDateTime now) {
        return status == ReservationWaitlistStatus.OFFERED
                && offerExpiresAt != null
                && !now.isBefore(offerExpiresAt);
    }

    private void requireActiveOffer(LocalDateTime respondedAt) {
        if (status != ReservationWaitlistStatus.OFFERED || offerExpiresAt == null) {
            throw new IllegalStateException("OFFERED 상태의 대기열만 응답할 수 있습니다.");
        }
        if (!respondedAt.isBefore(offerExpiresAt)) {
            throw new IllegalStateException("만료된 승급 제안에는 응답할 수 없습니다.");
        }
    }
}
