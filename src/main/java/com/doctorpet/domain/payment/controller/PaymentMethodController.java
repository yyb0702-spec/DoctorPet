package com.doctorpet.domain.payment.controller;

import com.doctorpet.domain.payment.dto.response.BillingKeyIssueResponse;
import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
import com.doctorpet.domain.payment.dto.response.PaymentMethodResponse;
import com.doctorpet.domain.payment.service.BillingKeyIssueApplicationService;
import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

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
    private final BillingKeyIssueApplicationService billingKeyIssueApplicationService;

    @Value("${payment.portone.billing-key-result-url:http://localhost:5173/payment-methods}")
    private String billingKeyResultUrl;

    @PostMapping("/billing-key-issues")
    public ResponseEntity<ApiResponse<BillingKeyIssueResponse>> issueBillingKey(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        String issueId = billingKeyIssueApplicationService.issue(principal.memberId());
        // issueId는 서버가 발급·Redis에 보관한 난수다. URL에는 이 값만 싣고 빌링키는
        // PortOne이 이 API 콜백으로 전달한다.
        String redirectUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/payment-methods/billing-key-issues/{issueId}/callback")
                .buildAndExpand(issueId)
                .toUriString();
        BillingKeyIssueResponse response = new BillingKeyIssueResponse(issueId, redirectUrl);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/billing-key-issues/{issueId}/callback")
    public void completeBillingKeyIssue(
            @PathVariable String issueId,
            @RequestParam(required = false) String billingKey,
            HttpServletResponse response
    ) throws java.io.IOException {
        boolean completed = false;
        try {
            if (billingKey != null && !billingKey.isBlank()) {
                billingKeyIssueApplicationService.complete(issueId, billingKey);
                completed = true;
            } else {
                // PortOne 취소·실패 콜백도 발급 시도를 즉시 폐기해 늦게 도착한 성공 결과가
                // 같은 issueId를 재사용하지 못하게 한다.
                billingKeyIssueApplicationService.discard(issueId);
            }
        } catch (RuntimeException ignored) {
            // 콜백 URL의 빌링키는 로그·응답으로 다시 내보내지 않는다. 사용자는 결과 화면에서
            // 재시도하고, 서버 측 감사·관측은 원문 없이 별도 에러 코드로 처리한다.
        }
        String target = UriComponentsBuilder.fromUriString(billingKeyResultUrl)
                .queryParam("billingKeyResult", completed ? "success" : "failed")
                .build(true)
                .toUriString();
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", target);
    }

    /** PC iframe 응답은 URL 대신 SDK Promise로 빌링키를 돌려준다. 원문은 인증된 HTTPS body로만 받고,
     * 서버가 발급한 1회성 issueId와 PortOne merchantId를 대조한 뒤 등록한다. */
    @PostMapping("/billing-key-issues/{issueId}/complete")
    public ResponseEntity<ApiResponse<PaymentMethodResponse>> completeBillingKeyIssueFromSdk(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable String issueId,
            @Valid @RequestBody PaymentMethodRegisterRequest request
    ) {
        PaymentMethodResponse completed = billingKeyIssueApplicationService.complete(
                principal.memberId(),
                issueId,
                request.billingKey()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(completed));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<PaymentMethodResponse>>> getMyPaymentMethods(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        List<PaymentMethodResponse> responses = paymentMethodService.getMyPaymentMethods(principal.memberId());
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @PatchMapping("/{paymentMethodId}/default")
    public ResponseEntity<ApiResponse<PaymentMethodResponse>> setDefault(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long paymentMethodId
    ) {
        PaymentMethodResponse response = paymentMethodService.setDefault(
                principal.memberId(),
                paymentMethodId
        );
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{paymentMethodId}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable Long paymentMethodId
    ) {
        paymentMethodService.delete(principal.memberId(), paymentMethodId);
        // 본문 없는 성공 응답은 204로 반환한다(코드컨벤션 — 생성 201·조회/수정 200·본문 없음 204).
        return ResponseEntity.noContent().build();
    }
}
