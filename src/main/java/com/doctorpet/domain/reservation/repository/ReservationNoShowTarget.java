package com.doctorpet.domain.reservation.repository;

import java.time.LocalDateTime;

/** 자동 노쇼 배치가 상태 전이와 커서 이동에 필요한 최소 조회 결과다. */
public interface ReservationNoShowTarget {

    Long getReservationId();

    LocalDateTime getSlotStartAt();
}
