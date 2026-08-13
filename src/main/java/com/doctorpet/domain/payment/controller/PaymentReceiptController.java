package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.response.PaymentReceiptResponse;
import com.doctorpet.domain.payment.service.PaymentReceiptService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  보호자 JSON 영수증 조회 API(SA §9-4 영수증). GET /api/payments/{paymentId}/receipt.
  회원 식별은 요청 body/path가 아니라 @AuthenticationPrincipal로만 하고, 본인 결제 여부는 서비스에서 재검증한다(보안).
  보호자 권한(ROLE_GUARDIAN) 강제는 SecurityConfig의 URL 매처에서 처리한다.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentReceiptController {

    private final PaymentReceiptService paymentReceiptService;

    @GetMapping("/{paymentId}/receipt")
    public ResponseEntity<ApiResponse<PaymentReceiptResponse>> getMyReceipt(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long paymentId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentReceiptService.getForGuardian(paymentId, principal.memberId())));
    }
}
