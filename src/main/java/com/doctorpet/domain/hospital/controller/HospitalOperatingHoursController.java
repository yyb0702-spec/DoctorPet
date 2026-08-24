package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.request.OperatingHoursUpdateRequest;
import com.doctorpet.domain.hospital.dto.request.TemporaryClosureCreateRequest;
import com.doctorpet.domain.hospital.dto.response.OperatingHoursResponse;
import com.doctorpet.domain.hospital.dto.response.TemporaryClosureResponse;
import com.doctorpet.domain.hospital.service.HospitalOperatingHoursApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hospital")
public class HospitalOperatingHoursController {

    private final HospitalOperatingHoursApplicationService service;

    @GetMapping("/operating-hours")
    public ResponseEntity<ApiResponse<OperatingHoursResponse>> getOperatingHours(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        OperatingHoursResponse response = service.getOperatingHours(principal.memberId());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/operating-hours/scheduled")
    public ResponseEntity<ApiResponse<List<OperatingHoursResponse>>> getScheduledOperatingHours(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        List<OperatingHoursResponse> response = service
                .getScheduledOperatingHours(principal.memberId());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/operating-hours")
    public ResponseEntity<ApiResponse<OperatingHoursResponse>> updateOperatingHours(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody OperatingHoursUpdateRequest request
    ) {
        OperatingHoursResponse response = service.updateOperatingHours(
                principal.memberId(),
                request
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/temporary-closures")
    public ResponseEntity<ApiResponse<TemporaryClosureResponse>> createTemporaryClosure(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody TemporaryClosureCreateRequest request
    ) {
        TemporaryClosureResponse response = service.createTemporaryClosure(
                principal.memberId(),
                request
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @DeleteMapping("/temporary-closures/{businessDate}")
    public ResponseEntity<Void> cancelTemporaryClosure(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate businessDate
    ) {
        service.cancelTemporaryClosure(principal.memberId(), businessDate);
        return ResponseEntity.noContent().build();
    }
}
