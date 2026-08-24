package com.doctorpet.domain.reservation.repository;

import java.time.LocalDateTime;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;

/** 자동 노쇼 배치가 상태 전이와 커서 이동에 필요한 최소 조회 결과다. */
public interface ReservationNoShowTarget {

    Long getReservationId();

    ReservationStatus getStatus();

    LocalDateTime getSlotStartAt();
}
