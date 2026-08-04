package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.request.ReservationRejectRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationNoShowRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationNoShowRestoreRequest;
import com.doctorpet.domain.reservation.dto.response.HospitalReservationListItemResponse;
import com.doctorpet.domain.reservation.dto.response.HospitalReservationPageResponse;
import com.doctorpet.domain.reservation.service.HospitalReservationApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/hospital/reservations")
public class HospitalReservationController {

    private final HospitalReservationApplicationService hospitalReservationService;

    @GetMapping
    public ApiResponse<HospitalReservationPageResponse> getReservations(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestParam(defaultValue = "REQUESTED") String status,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<HospitalReservationListItemResponse> result =
                hospitalReservationService.findHospitalReservations(
                        principal.memberId(), status, page, size
                );
        return ApiResponse.success(HospitalReservationPageResponse.from(result));
    }

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
        hospitalReservationService.reject(
                principal.memberId(),
                reservationId,
                request.toRejectReason()
        );
        return ApiResponse.success();
    }

    @PatchMapping("/{reservationId}/check-in")
    public ApiResponse<Void> checkIn(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId
    ) {
        hospitalReservationService.checkIn(principal.memberId(), reservationId);
        return ApiResponse.success();
    }

    @PatchMapping("/{reservationId}/start")
    public ApiResponse<Void> startTreatment(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId
    ) {
        hospitalReservationService.startTreatment(principal.memberId(), reservationId);
        return ApiResponse.success();
    }

    @PatchMapping("/{reservationId}/complete")
    public ApiResponse<Void> completeTreatment(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId
    ) {
        hospitalReservationService.completeTreatment(principal.memberId(), reservationId);
        return ApiResponse.success();
    }

    @PatchMapping("/{reservationId}/no-show")
    public ApiResponse<Void> confirmNoShow(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId,
            @Valid @RequestBody ReservationNoShowRequest request
    ) {
        hospitalReservationService.confirmNoShow(
                principal.memberId(),
                reservationId,
                request.reason()
        );
        return ApiResponse.success();
    }

    @PatchMapping("/{reservationId}/restore")
    public ApiResponse<Void> restoreNoShow(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId,
            @Valid @RequestBody ReservationNoShowRestoreRequest request
    ) {
        hospitalReservationService.restoreNoShow(
                principal.memberId(),
                reservationId,
                request.reason()
        );
        return ApiResponse.success();
    }
}
