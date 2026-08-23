// 병원 스태프 결제 목록 API(SA §8-7). 자병원 예약의 활성 결제를 페이지로 조회한다.
// 회원 식별·병원 스코프는 @AuthenticationPrincipal로만 한다(요청 값 신뢰 금지). 스태프 권한은
// SecurityConfig의 /api/hospital/** 매처가 강제한다.
package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.response.HospitalPaymentPageResponse;
import com.doctorpet.domain.payment.service.HospitalPaymentQueryService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hospital/payments")
@RequiredArgsConstructor
@Validated
public class HospitalPaymentQueryController {

    private final HospitalPaymentQueryService hospitalPaymentQueryService;

    @GetMapping
    public ApiResponse<HospitalPaymentPageResponse> getHospitalPayments(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(HospitalPaymentPageResponse.from(
                hospitalPaymentQueryService.getHospitalPayments(
                        principal.memberId(),
                        PageRequest.of(page, size)
                )
        ));
    }
}
