// 병원 스태프의 회원 진료·결제 이력 API(SA §8-6). 예약으로 회원을 해석해 자병원 이력만 페이지로 조회한다.
// 회원 식별·병원 스코프는 @AuthenticationPrincipal과 예약 소유 검증으로만 한다(요청 값 신뢰 금지).
// 스태프 권한은 SecurityConfig의 /api/hospital/** 매처가 강제한다.
package com.doctorpet.domain.reservation.controller;

import com.doctorpet.domain.reservation.dto.response.HospitalMemberHistoryPageResponse;
import com.doctorpet.domain.reservation.service.HospitalMemberHistoryQueryService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hospital/reservations/{reservationId}/member-history")
@RequiredArgsConstructor
@Validated
public class HospitalMemberHistoryController {

    private final HospitalMemberHistoryQueryService hospitalMemberHistoryQueryService;

    @GetMapping
    public ApiResponse<HospitalMemberHistoryPageResponse> getMemberHistory(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(HospitalMemberHistoryPageResponse.from(
                hospitalMemberHistoryQueryService.getMemberHistory(
                        principal.memberId(),
                        reservationId,
                        PageRequest.of(page, size)
                )
        ));
    }
}
