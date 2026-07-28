package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hospitals")
public class HospitalController {

    private final HospitalService hospitalService;

    @GetMapping("/{hospitalId}")
    public ResponseEntity<ApiResponse<HospitalDetailResponse>> getHospitalDetail(
            @PathVariable Long hospitalId
    ) {
        HospitalDetailResponse response = hospitalService.getHospitalDetail(hospitalId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
