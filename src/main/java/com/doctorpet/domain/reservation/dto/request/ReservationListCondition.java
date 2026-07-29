package com.doctorpet.domain.reservation.dto.request;

import java.time.LocalDate;

public record ReservationListCondition (
        String status,
        LocalDate from,
        LocalDate to,
        int page,
        int size,
        String sort
){
}
