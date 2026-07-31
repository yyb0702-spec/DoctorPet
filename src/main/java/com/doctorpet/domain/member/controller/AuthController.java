package com.doctorpet.domain.member.controller;

import com.doctorpet.domain.member.dto.request.EmailRequest;
import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.PasswordResetConfirmRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.request.SignupRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.dto.response.SignupResponse;
import com.doctorpet.domain.member.service.AuthService;
import com.doctorpet.domain.member.service.EmailVerificationService;
import com.doctorpet.domain.member.service.PasswordResetService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
  인증 관련 API. SA §8-1 — 회원가입·로그인·토큰 재발급·로그아웃·이메일 인증·비밀번호 재설정
  (이메일 인증·비밀번호 재설정은 백로그 P2, 이번에 함께 구현).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        SignupResponse response = authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<LoginResponse>> reissue(@Valid @RequestBody ReissueRequest request) {
        LoginResponse response = authService.reissue(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal MemberPrincipal principal) {
        authService.logout(principal.memberId());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // 프론트가 아직 없어 이메일 링크가 이 백엔드 엔드포인트를 직접 가리킨다(임시). 프론트가
    // 생기면 프론트 페이지가 이 API를 fetch로 호출하는 구조로 바뀌어야 한다.
    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        emailVerificationService.verifyEmail(token);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // 계정 존재 여부·인증 여부를 노출하지 않기 위해 항상 200을 반환한다(EmailVerificationService
    // 참고) — 실제로 메일이 발송됐는지는 응답만으로 알 수 없다.
    @PostMapping("/verify-email/resend")
    public ResponseEntity<ApiResponse<Void>> resendVerificationEmail(@Valid @RequestBody EmailRequest request) {
        emailVerificationService.resendVerificationEmail(request.email());
        return ResponseEntity.ok(ApiResponse.success());
    }

    // 계정 존재 여부를 노출하지 않기 위해 항상 200을 반환한다(PasswordResetService 참고).
    @PostMapping("/password-reset/request")
    public ResponseEntity<ApiResponse<Void>> requestPasswordReset(@Valid @RequestBody EmailRequest request) {
        passwordResetService.requestPasswordReset(request.email());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse<Void>> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmPasswordReset(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.success());
    }
}
