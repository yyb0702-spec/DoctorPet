package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationDetailResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationPageResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.service.ReservationApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationApplicationService reservationApplicationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReservationResponse> request(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody ReservationRequest request
    ) {
        return ApiResponse.success(
                reservationApplicationService.request(principal.memberId(), request)
        );
    }

    @PatchMapping("/{reservationId}/cancel")
    public ApiResponse<Void> cancel(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId
    ) {
        reservationApplicationService.cancel(principal.memberId(), reservationId);
        return ApiResponse.success();
    }

    @GetMapping
    public ApiResponse<ReservationPageResponse> getMyReservations(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "reservedAt,desc") String sort
    ){
        ReservationListCondition condition = new ReservationListCondition(
                status,
                from,
                to,
                page,
                size,
                sort
        );

        return ApiResponse.success(
                reservationApplicationService.getMyReservations(
                        principal.memberId(),
                        condition
                )
        );
    }

    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationDetailResponse> getMyReservation(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId
    ){
        return ApiResponse.success(
                reservationApplicationService.getMyReservation(
                        principal.memberId(),
                        reservationId
                )
        );
    }
}
