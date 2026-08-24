package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.request.PaymentRefundRequest;
import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.service.PaymentRefundService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  병원 진료비 환불 API(SA §8-7, 이슈 #37). POST /api/hospital/payments/{paymentId}/refund.
  빌링키 자동 결제 완료(PAID·BILLING_KEY) 건을 전액 환불(REFUNDED)한다. 스태프 식별은 @AuthenticationPrincipal로만
  하고, 자병원 검증은 서비스에서 예약(port) 기준으로 재검증한다. ROLE_HOSPITAL_STAFF는 SecurityConfig의
  /api/hospital/** 매처가 강제한다.

  PATCH가 아니라 POST다 — 오프라인 정산(PATCH)은 기존 결제 레코드의 상태를 고쳐 쓰는 동작이지만, 환불은
  PG 취소라는 외부 부수효과와 환불 이력(payment_refunds) 생성을 동반하는 새 처리의 실행이다.
  이미 환불된 결제에 재요청하면 PG를 호출하지 않고 현재 상태를 반환한다(멱등 200).
 */
@RestController
@RequestMapping("/api/hospital/payments")
@RequiredArgsConstructor
public class HospitalPaymentRefundController {

    private final PaymentRefundService paymentRefundService;

    @PostMapping("/{paymentId}/refund")
    public ResponseEntity<ApiResponse<PaymentHistoryResponse>> refund(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long paymentId,
            @Valid @RequestBody PaymentRefundRequest request
    ) {
        PaymentHistoryResponse response =
                paymentRefundService.refund(paymentId, principal.memberId(), request.reason());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
