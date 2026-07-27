package com.doctorpet.domain.member.controller;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  회원 프로필 API. SA §8-1 — 이 Issue 범위는 내 정보 조회까지다.
  회원 탈퇴(DELETE /api/members/me)는 활성 예약·미수금 처리 정책(SA 부록A #5)이
  C/D 도메인과 합의되기 전까지 별도 Issue로 보류한다.
 */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MemberResponse>> getMyInfo(@AuthenticationPrincipal MemberPrincipal principal) {
        MemberResponse response = memberService.getMyInfo(principal.memberId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
