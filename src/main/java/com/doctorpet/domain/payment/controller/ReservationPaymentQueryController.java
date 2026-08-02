package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  보호자 결제 내역 조회 API(SA §8-7, 이슈 #47). GET /api/reservations/{reservationId}/payments.
  회원 식별은 요청 body/path가 아니라 @AuthenticationPrincipal로만 하고, 본인 예약 여부는 서비스에서 재검증한다(보안).
  보호자 권한(ROLE_GUARDIAN) 강제는 SecurityConfig의 URL 매처에서 처리한다.
 */
@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationPaymentQueryController {

    private final PaymentQueryService paymentQueryService;

    @GetMapping("/{reservationId}/payments")
    public ResponseEntity<ApiResponse<List<PaymentHistoryResponse>>> getMyPayments(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId
    ) {
        List<PaymentHistoryResponse> payments =
                paymentQueryService.getForGuardian(reservationId, principal.memberId());
        return ResponseEntity.ok(ApiResponse.success(payments));
    }
}
