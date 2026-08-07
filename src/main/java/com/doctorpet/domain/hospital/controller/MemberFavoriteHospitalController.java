package com.doctorpet.domain.hospital.controller;

import com.doctorpet.domain.hospital.dto.response.FavoriteHospitalPageResponse;
import com.doctorpet.domain.hospital.service.HospitalFavoriteService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/members/me/favorite-hospitals")
public class MemberFavoriteHospitalController {

    private final HospitalFavoriteService hospitalFavoriteService;

    @GetMapping
    public ResponseEntity<ApiResponse<FavoriteHospitalPageResponse>>
    getMyFavoriteHospitals(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        FavoriteHospitalPageResponse response =
                hospitalFavoriteService.getMyFavorites(
                        principal.memberId(),
                        page,
                        size
                );

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
