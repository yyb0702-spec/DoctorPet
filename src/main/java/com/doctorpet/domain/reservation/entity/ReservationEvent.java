package com.doctorpet.domain.reservation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
}
