package com.doctorpet.domain.reservation.entity;

import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reservation_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReservationEventType eventType;

    @Column(length = 255)
    private String memo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    private ReservationEvent(
            Reservation reservation,
            ReservationEventType eventType,
            String memo,
            LocalDateTime occurredAt
    ) {
        this.reservation = reservation;
        this.eventType = eventType;
        this.memo = memo;
        this.occurredAt = occurredAt;
    }

    public static ReservationEvent create(
            Reservation reservation,
            ReservationEventType eventType,
            String memo,
            LocalDateTime occurredAt
    ) {
        return new ReservationEvent(
                reservation,
                eventType,
                memo,
                occurredAt
        );
    }
}
