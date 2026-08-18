package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.request.PaymentRechargeRequest;
import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.service.PaymentApplicationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  보호자 결제 실패 셀프 복구(다시 결제) API(고도화 3.3, SA §9-4).
  POST /api/reservations/{reservationId}/payments/recharge.

  OFFLINE_REQUIRED로 남은 결제를 보호자가 새 ACTIVE 결제수단으로 다시 결제한다. 회원 식별은 요청 body/path가 아니라
  @AuthenticationPrincipal로만 하고, 본인 예약 여부는 서비스에서 재검증한다(보안). 보호자 권한(ROLE_GUARDIAN) 강제는
  SecurityConfig의 URL 매처에서 처리한다 — GET /payments 매처는 GET만 가드하므로 이 POST 경로 매처를 별도로 둔다.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationPaymentRechargeController {

    private final PaymentApplicationService paymentApplicationService;

    @PostMapping("/{reservationId}/payments/recharge")
    public ResponseEntity<ApiResponse<PaymentChargeResponse>> recharge(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId,
            @Valid @RequestBody PaymentRechargeRequest request
    ) {
        PaymentChargeResponse response = paymentApplicationService.recharge(
                reservationId, principal.memberId(), request.paymentMethodId());
        // 새 결제 레코드 생성이므로 201. 승인 실패도 레코드는 생성되며 status(OFFLINE_REQUIRED 등)로 결과를 표현한다.
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
