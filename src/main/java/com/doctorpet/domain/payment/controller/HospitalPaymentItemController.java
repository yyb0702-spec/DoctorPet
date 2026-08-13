package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.request.PaymentItemSaveRequest;
import com.doctorpet.domain.payment.dto.response.PaymentItemResponse;
import com.doctorpet.domain.payment.service.PaymentItemCommand;
import com.doctorpet.domain.payment.service.PaymentItemDraftService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
  병원 스태프 청구 항목 초안 API(SA §9-4 청구 항목). 진료 완료 후 청구 전까지 항목을 작성·수정한다.
  - GET  /api/hospital/reservations/{reservationId}/payment-items — 초안 조회
  - PUT  /api/hospital/reservations/{reservationId}/payment-items — 초안 전체 교체
  부분 수정(개별 PATCH·DELETE) 대신 전체 교체를 쓴다(병원 진료역량 전체 교체 API와 같은 패턴).
  스태프 식별은 요청 body/path가 아니라 @AuthenticationPrincipal로만 하고, 자병원 여부는 서비스에서 재검증한다(보안).
  ROLE_HOSPITAL_STAFF 강제는 SecurityConfig의 /api/hospital/** 매처가 처리한다.
 */
@RestController
@RequestMapping("/api/hospital/reservations")
@RequiredArgsConstructor
public class HospitalPaymentItemController {

    private final PaymentItemDraftService paymentItemDraftService;

    @GetMapping("/{reservationId}/payment-items")
    public ResponseEntity<ApiResponse<List<PaymentItemResponse>>> getDrafts(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                paymentItemDraftService.getDrafts(reservationId, principal.memberId())));
    }

    @PutMapping("/{reservationId}/payment-items")
    public ResponseEntity<ApiResponse<List<PaymentItemResponse>>> replaceDrafts(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long reservationId,
            @Valid @RequestBody PaymentItemSaveRequest request
    ) {
        // 요청 DTO를 서비스까지 넘기지 않고 도메인 커맨드로 변환한다. 항목 금액·총액은 여기서 계산하지 않는다 —
        // 서버 산출 지점을 PaymentAmountPolicy 한 곳으로 모아 우회 경로를 만들지 않기 위함이다(SA §9-4).
        List<PaymentItemCommand> items = request.items().stream()
                .map(item -> new PaymentItemCommand(item.name(), item.quantity(), item.unitPrice()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(
                paymentItemDraftService.replaceDrafts(reservationId, principal.memberId(), items)));
    }
}
