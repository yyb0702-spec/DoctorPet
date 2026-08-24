package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.request.HospitalCapabilitiesUpdateRequest;
import com.doctorpet.domain.hospital.dto.response.HospitalCapabilitiesResponse;
import com.doctorpet.domain.hospital.service.HospitalCapabilityApplicationService;
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
@RequestMapping("/api/hospital/capabilities")
public class HospitalCapabilityController {

    private final HospitalCapabilityApplicationService service;

    @GetMapping
    public ResponseEntity<ApiResponse<HospitalCapabilitiesResponse>> getCapabilities(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        HospitalCapabilitiesResponse response = service.getCapabilities(principal.memberId());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<HospitalCapabilitiesResponse>> updateCapabilities(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody HospitalCapabilitiesUpdateRequest request
    ) {
        HospitalCapabilitiesResponse response = service.updateCapabilities(principal.memberId(), request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
