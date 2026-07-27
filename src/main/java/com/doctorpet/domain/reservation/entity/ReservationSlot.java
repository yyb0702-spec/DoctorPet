package com.doctorpet.domain.reservation.entity;

import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.global.exception.ServiceException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservation_slots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_slot_hospital_time",
                        columnNames = {"hospital_id", "start_at", "end_at"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long hospitalId;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationSlotStatus status;

    @Version
    private Long version;

    public void occupy(){
        if (status != ReservationSlotStatus.AVAILABLE){
            throw new ServiceException(...);
        }

        status = ReservationSlotStatus.OCCUPIED;
    }
    public void release(){
        if (status != ReservationSlotStatus.OCCUPIED){
            throw new ServiceException(...);
        }

        status = ReservationSlotStatus.AVAILABLE;
    }
}
