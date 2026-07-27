package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> request(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestBody ReservationRequest request
    ) {
        return ApiResponse.success(
                reservationService.request(principal.memberId(), request)
        );
    }

    @PatchMapping("/{reservationId}/cancel")
    public ApiResponse<Void> cancel(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId
    ) {
        reservationService.cancel(principal.memberId(), reservationId);
        return ApiResponse.success();
    }
}
