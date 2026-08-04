package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.service.PaymentOfflineSettlementService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  병원 오프라인 정산 API(SA §8-7, 이슈 #36). PATCH /api/hospital/payments/{paymentId}/offline-settle.
  OFFLINE_REQUIRED 결제를 현장 수납 완료(OFFLINE_PAID)로 확정한다. 스태프 식별은 @AuthenticationPrincipal로만 하고,
  자병원 검증은 서비스에서 예약(port) 기준으로 재검증한다. ROLE_HOSPITAL_STAFF는 SecurityConfig의 /api/hospital/** 매처가 강제한다.
 */
@RestController
@RequestMapping("/api/hospital/payments")
@RequiredArgsConstructor
public class HospitalPaymentSettlementController {

    private final PaymentOfflineSettlementService paymentOfflineSettlementService;

    @PatchMapping("/{paymentId}/offline-settle")
    public ResponseEntity<ApiResponse<PaymentHistoryResponse>> settleOffline(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long paymentId
    ) {
        PaymentHistoryResponse response =
                paymentOfflineSettlementService.settle(paymentId, principal.memberId());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
