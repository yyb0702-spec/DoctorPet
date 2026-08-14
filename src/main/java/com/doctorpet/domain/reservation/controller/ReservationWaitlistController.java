package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationWaitlistCreateRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationWaitlistAcceptRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationWaitlistResponse;
import com.doctorpet.domain.reservation.service.ReservationWaitlistService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

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

    @GetMapping
    public ApiResponse<List<ReservationWaitlistResponse>> getMyWaitlists(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return ApiResponse.success(reservationWaitlistService.getMyWaitlists(principal.memberId()));
    }

    @GetMapping("/{waitlistId}")
    public ApiResponse<ReservationWaitlistResponse> getMyWaitlist(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long waitlistId
    ) {
        return ApiResponse.success(reservationWaitlistService.getMyWaitlist(principal.memberId(), waitlistId));
    }

    @DeleteMapping("/{waitlistId}")
    public ApiResponse<Void> cancel(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long waitlistId
    ) {
        reservationWaitlistService.cancel(principal.memberId(), waitlistId);
        return ApiResponse.success();
    }

    @PostMapping("/{waitlistId}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> accept(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long waitlistId,
            @Valid @RequestBody ReservationWaitlistAcceptRequest request
    ) {
        return ApiResponse.success(reservationWaitlistService.accept(
                principal.memberId(), waitlistId, request.petId(), request.paymentMethodId()));
    }

    @PatchMapping("/{waitlistId}/reject")
    public ApiResponse<Void> reject(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long waitlistId
    ) {
        reservationWaitlistService.reject(principal.memberId(), waitlistId);
        return ApiResponse.success();
    }
}
