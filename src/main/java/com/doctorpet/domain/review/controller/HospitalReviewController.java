package com.doctorpet.domain.review.controller;

import com.doctorpet.domain.review.dto.response.ReviewPageResponse;
import com.doctorpet.domain.review.service.ReviewApplicationService;
import com.doctorpet.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hospitals/{hospitalId}/reviews")
public class HospitalReviewController {

    private final ReviewApplicationService reviewApplicationService;

    @GetMapping
    public ResponseEntity<ApiResponse<ReviewPageResponse>> getHospitalReviews(
            @PathVariable Long hospitalId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        ReviewPageResponse response = reviewApplicationService
                .getHospitalReviews(hospitalId, page, size);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
