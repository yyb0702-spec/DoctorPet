package com.doctorpet.domain.member.controller;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.member.service.MemberWithdrawalApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  회원 프로필 API. SA §8-1.
  회원 탈퇴는 활성 예약(CONFIRMED·CHECKED_IN) 보유 여부만 확인한다(SA §6-3, 부록A 확정 —
  탈퇴 보류 정책). 미수금(OFFLINE_REQUIRED) 체크는 결제(청구) 도메인이 아직 없어 포함되지
  않았다 — MemberWithdrawalApplicationService 주석 참고.
 */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final MemberWithdrawalApplicationService memberWithdrawalApplicationService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MemberResponse>> getMyInfo(@AuthenticationPrincipal MemberPrincipal principal) {
        MemberResponse response = memberService.getMyInfo(principal.memberId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(@AuthenticationPrincipal MemberPrincipal principal) {
        memberWithdrawalApplicationService.withdraw(principal.memberId());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
