package com.doctorpet.domain.review.controller;

import com.doctorpet.domain.review.dto.request.ReviewRequest;
import com.doctorpet.domain.review.dto.response.ReviewResponse;
import com.doctorpet.domain.review.service.ReviewApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ReviewController {

    private final ReviewApplicationService reviewApplicationService;

    @PostMapping("/reservations/{reservationId}/reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> create(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId,
            @Valid @RequestBody ReviewRequest request
    ) {
        ReviewResponse response = reviewApplicationService.create(
                principal.memberId(),
                reservationId,
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @PutMapping("/reviews/{reviewId}")
    public ResponseEntity<ApiResponse<ReviewResponse>> update(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reviewId,
            @Valid @RequestBody ReviewRequest request
    ) {
        ReviewResponse response = reviewApplicationService.update(
                principal.memberId(),
                reviewId,
                request
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reviewId
    ) {
        reviewApplicationService.delete(principal.memberId(), reviewId);
        return ResponseEntity.noContent().build();
    }

}
