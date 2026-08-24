package com.doctorpet.domain.reservation.entity;

import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.global.exception.ServiceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reservation_slots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_slot_hospital_start",
                        columnNames = {"hospital_id", "start_at"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    /*
     * 기존 슬롯 백필 전에 Hibernate가 nullable 컬럼을 먼저 생성한다.
     * NOT NULL 전환은 ReservationSlotBusinessDateMigrationRunner가 담당한다.
     */
    @Column(name = "business_date")
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationSlotStatus status;

    @Version
    private Long version;

    private ReservationSlot(
            Long hospitalId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDate businessDate
    ) {
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException(
                    "슬롯 시작 시간은 종료 시간보다 빨라야 합니다."
            );
        }

        this.hospitalId = hospitalId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.businessDate = businessDate;
        this.status = ReservationSlotStatus.OPEN;
    }

    public static ReservationSlot create(
            Long hospitalId,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        return create(hospitalId, startAt, endAt, startAt.toLocalDate());
    }

    public static ReservationSlot create(
            Long hospitalId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDate businessDate
    ) {
        if (businessDate == null) {
            throw new IllegalArgumentException("영업 기준일이 필요합니다.");
        }
        return new ReservationSlot(hospitalId, startAt, endAt, businessDate);
    }

    public void reserve() {
        if (status != ReservationSlotStatus.OPEN) {
            throw new ServiceException(SlotErrorCode.ALREADY_RESERVED);
        }

        this.status = ReservationSlotStatus.RESERVED;
    }

    public void open() {
        if (status != ReservationSlotStatus.RESERVED) {
            throw new ServiceException(SlotErrorCode.INVALID_STATUS);
        }

        this.status = ReservationSlotStatus.OPEN;
    }
}
