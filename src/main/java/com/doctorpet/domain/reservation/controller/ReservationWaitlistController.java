package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationWaitlistCreateRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationWaitlistResponse;
import com.doctorpet.domain.reservation.service.ReservationWaitlistService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservation-waitlists")
public class ReservationWaitlistController {

    private final ReservationWaitlistService reservationWaitlistService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationWaitlistResponse> register(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody ReservationWaitlistCreateRequest request
    ) {
        return ApiResponse.success(
                reservationWaitlistService.register(principal.memberId(), request.slotId())
        );
    }
}
