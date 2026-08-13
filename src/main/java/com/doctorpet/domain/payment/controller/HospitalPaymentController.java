package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.request.PaymentChargeRequest;
import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.service.PaymentApplicationService;
import com.doctorpet.domain.payment.service.PaymentItemCommand;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  병원 스태프 진료비 청구 API(SA §8-7, 이슈 #34). POST /api/hospital/reservations/{reservationId}/payments.
  스태프 식별은 요청 body/path가 아니라 @AuthenticationPrincipal로만 한다(자병원 검증은 principal 기반, 보안).
  ROLE_HOSPITAL_STAFF 강제는 SecurityConfig의 URL 매처에서 처리한다.
 */
@RestController
@RequestMapping("/api/hospital/reservations")
@RequiredArgsConstructor
public class HospitalPaymentController {

    private final PaymentApplicationService paymentApplicationService;
    private final PaymentQueryService paymentQueryService;

    @PostMapping("/{reservationId}/payments")
    public ResponseEntity<ApiResponse<PaymentChargeResponse>> charge(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId,
            @Valid @RequestBody PaymentChargeRequest request
    ) {
        // 요청 DTO를 서비스까지 넘기지 않고 도메인 커맨드로 변환한다. 총액은 여기서 계산하지 않는다 —
        // 서버 계산 지점을 청구 트랜잭션(Tx1) 한 곳으로 모아 우회 경로를 만들지 않기 위함이다(고도화 결제 3.1).
        List<PaymentItemCommand> items = request.items().stream()
                .map(item -> new PaymentItemCommand(item.name(), item.quantity(), item.unitPrice()))
                .toList();
        PaymentChargeResponse response = paymentApplicationService.charge(
                reservationId, principal.memberId(), items);
        // 결제 레코드 생성이므로 201. 승인 실패도 레코드는 생성되며 status(OFFLINE_REQUIRED 등)로 결과를 표현한다.
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{reservationId}/payments")
    public ResponseEntity<ApiResponse<List<PaymentHistoryResponse>>> getReservationPayments(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId
    ) {
        List<PaymentHistoryResponse> payments =
                paymentQueryService.getForHospital(reservationId, principal.memberId());
        return ResponseEntity.ok(ApiResponse.success(payments));
    }
}
