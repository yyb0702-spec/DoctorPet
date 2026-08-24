package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotLookupResponse;
import com.doctorpet.domain.hospital.service.HospitalFavoriteService;
import com.doctorpet.domain.hospital.service.HospitalDetailApplicationService;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.hospital.service.HospitalSlotApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/hospitals")
public class HospitalController {

    private final HospitalService hospitalService;
    private final HospitalDetailApplicationService hospitalDetailApplicationService;
    private final HospitalFavoriteService hospitalFavoriteService;
    private final HospitalSlotApplicationService hospitalSlotApplicationService;

    @GetMapping("/{hospitalId}")
    public ResponseEntity<ApiResponse<HospitalDetailResponse>> getHospitalDetail(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long hospitalId
    ) {
        Long memberId = guardianMemberId(principal);
        HospitalDetailResponse response = hospitalDetailApplicationService.getHospitalDetail(
                hospitalId,
                memberId
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{hospitalId}/favorite")
    public ResponseEntity<ApiResponse<Void>> addFavorite(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long hospitalId
    ) {
        hospitalFavoriteService.addFavorite(
                principal.memberId(),
                hospitalId
        );

        return ResponseEntity.ok(ApiResponse.success());
    }

    @DeleteMapping("/{hospitalId}/favorite")
    public ResponseEntity<Void> removeFavorite(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long hospitalId
    ) {
        hospitalFavoriteService.removeFavorite(
                principal.memberId(),
                hospitalId
        );

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{hospitalId}/slots")
    public ResponseEntity<ApiResponse<HospitalSlotLookupResponse>>
    getHospitalSlots(
            @PathVariable Long hospitalId,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        HospitalSlotLookupResponse response = hospitalSlotApplicationService.getHospitalSlots(hospitalId, date);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<HospitalSearchPageResponse>> searchHospitals(
            @AuthenticationPrincipal MemberPrincipal principal,
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
        Long memberId = guardianMemberId(principal);
        HospitalSearchPageResponse response = hospitalService.hospitalSearch(
                memberId,
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

    private Long guardianMemberId(MemberPrincipal principal) {
        return principal != null && "GUARDIAN".equals(principal.role())
                ? principal.memberId()
                : null;
    }
}
