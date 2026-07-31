package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationRejectRequest;
import com.doctorpet.domain.reservation.service.HospitalReservationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/hospital/reservations")
public class HospitalReservationController {

    private final HospitalReservationService hospitalReservationService;

    @PatchMapping("/{reservationId}/approve")
    public ApiResponse<Void> approve(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId
    ) {
        hospitalReservationService.approve(principal.memberId(), reservationId);
        return ApiResponse.success();
    }

    @PatchMapping("/{reservationId}/reject")
    public ApiResponse<Void> reject(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId,
            @Valid @RequestBody ReservationRejectRequest request
    ) {
        hospitalReservationService.reject(principal.memberId(), reservationId, request.rejectReason());
        return ApiResponse.success();
    }
}
