package com.doctorpet.domain.reservation.dto.request;

public record ReservationRequest(
        Long petId,
        Long slotId,
        Long paymentMethodId,
        String petNameSnapshot,
        String petSpeciesSnapshot
){
}
