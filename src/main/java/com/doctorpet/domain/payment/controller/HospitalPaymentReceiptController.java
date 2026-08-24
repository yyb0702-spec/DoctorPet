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
  병원 스태프 JSON 영수증 조회 API(SA §9-4 영수증). GET /api/hospital/payments/{paymentId}/receipt.
  오프라인 정산·환불과 같은 /api/hospital/payments/{paymentId}/* 경로 규약을 따른다.
  스태프 식별은 @AuthenticationPrincipal로만 하고, 자병원 결제 여부는 서비스에서 재검증한다(보안).
  ROLE_HOSPITAL_STAFF 강제는 SecurityConfig의 /api/hospital/** 매처가 처리한다.
 */
@RestController
@RequestMapping("/api/hospital/payments")
@RequiredArgsConstructor
public class HospitalPaymentReceiptController {

    private final PaymentReceiptService paymentReceiptService;

    @GetMapping("/{paymentId}/receipt")
    public ResponseEntity<ApiResponse<PaymentReceiptResponse>> getReceipt(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long paymentId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentReceiptService.getForHospital(paymentId, principal.memberId())));
    }
}
