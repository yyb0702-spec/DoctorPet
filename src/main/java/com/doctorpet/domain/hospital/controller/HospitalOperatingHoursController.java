package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.request.OperatingHoursUpdateRequest;
import com.doctorpet.domain.hospital.dto.response.OperatingHoursResponse;
import com.doctorpet.domain.hospital.service.HospitalOperatingHoursApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hospital/operating-hours")
public class HospitalOperatingHoursController {

    private final HospitalOperatingHoursApplicationService service;

    @GetMapping
    public ResponseEntity<ApiResponse<OperatingHoursResponse>> getOperatingHours(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        OperatingHoursResponse response = service.getOperatingHours(principal.memberId());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping
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
}
