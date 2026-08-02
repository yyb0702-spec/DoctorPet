package com.doctorpet.domain.ai.controller;

import com.doctorpet.domain.ai.dto.request.AiConsultationRequest;
import com.doctorpet.domain.ai.dto.response.AiConsultationResponse;
import com.doctorpet.domain.ai.service.AiConsultationService;
import com.doctorpet.domain.ai.service.AiRateLimiter;
import com.doctorpet.domain.ai.support.AiClientIpResolver;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/consultations")
@RequiredArgsConstructor
public class AiConsultationController {

    private final AiConsultationService aiConsultationService;
    private final AiRateLimiter aiRateLimiter;
    private final AiClientIpResolver clientIpResolver;

    @PostMapping
    public ResponseEntity<ApiResponse<AiConsultationResponse>> consult(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody AiConsultationRequest request,
            HttpServletRequest httpRequest
    ) {
        Long memberId = principal == null ? null : principal.memberId();
        aiRateLimiter.check(memberId, clientIpResolver.resolve(httpRequest));
        AiConsultationResponse response = aiConsultationService.consult(memberId, request);

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
