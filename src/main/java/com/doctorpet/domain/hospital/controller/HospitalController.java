package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.global.response.ApiResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@Validated
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

    @GetMapping
    public ResponseEntity<ApiResponse<HospitalSearchPageResponse>> searchHospitals(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String region,
            @RequestParam(required = false)
            @DecimalMin(value = "-90.0")
            @DecimalMax(value = "90.0")
            BigDecimal latitude,
            @RequestParam(required = false)
            @DecimalMin(value = "-180.0")
            @DecimalMax(value = "180.0")
            BigDecimal longitude,
            @RequestParam(required = false)
            @Positive
            BigDecimal radiusKm,
            @RequestParam(required = false) List<String> requiredCapabilities,
            @RequestParam(required = false) List<String> supportedSpecies,
            @RequestParam(required = false) Boolean surgery,
            @RequestParam(required = false) Boolean hospitalization,
            @RequestParam(required = false) Boolean nightCare,
            @RequestParam(required = false) Boolean emergency,
            @RequestParam(defaultValue = "false") boolean partnerOnly,
            @RequestParam(name = "openNow", defaultValue = "false")
            boolean openNowOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "name") String sort
    ) {
        HospitalSearchPageResponse response = hospitalService.hospitalSearch(
                keyword,
                region,
                latitude,
                longitude,
                radiusKm,
                requiredCapabilities,
                supportedSpecies,
                surgery,
                hospitalization,
                nightCare,
                emergency,
                partnerOnly,
                openNowOnly,
                page,
                size,
                sort
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
