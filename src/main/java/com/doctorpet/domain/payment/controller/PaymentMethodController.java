package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
import com.doctorpet.domain.payment.dto.response.PaymentMethodResponse;
import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  보호자 결제수단 API(SA §8-7, 이슈 #33). 등록·조회·삭제.
  회원 식별은 요청 body/path가 아니라 @AuthenticationPrincipal로만 한다(AGENTS 보안).
  보호자 권한(ROLE_GUARDIAN) 강제는 SecurityConfig의 URL 매처에서 처리한다.
 */
@RestController
@RequestMapping("/api/payment-methods")
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentMethodResponse>> register(
            @AuthenticationPrincipal MemberPrincipal principal,
            @Valid @RequestBody PaymentMethodRegisterRequest request
    ) {
        PaymentMethodResponse response = paymentMethodService.register(principal.memberId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentMethodResponse>>> getMyPaymentMethods(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        List<PaymentMethodResponse> responses = paymentMethodService.getMyPaymentMethods(principal.memberId());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @DeleteMapping("/{paymentMethodId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long paymentMethodId
    ) {
        paymentMethodService.delete(principal.memberId(), paymentMethodId);
        return ResponseEntity.ok(ApiResponse.success());
    }
}
