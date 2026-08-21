package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentMethodResponse;
import com.doctorpet.domain.payment.exception.PaymentMethodErrorCode;
import com.doctorpet.domain.payment.repository.BillingKeyIssueRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** PortOne SDK 발급 시도 생성과 비인증 모바일 콜백 소비를 연결한다. */
@Service
@RequiredArgsConstructor
public class BillingKeyIssueApplicationService {

    private final BillingKeyIssueRepository billingKeyIssueRepository;
    private final PaymentMethodService paymentMethodService;

    public String issue(Long memberId) {
        return billingKeyIssueRepository.issue(memberId);
    }

    public PaymentMethodResponse complete(String issueId, String billingKey) {
        Long memberId = billingKeyIssueRepository.consume(issueId)
                .orElseThrow(() -> new ServiceException(
                        PaymentMethodErrorCode.BILLING_KEY_ISSUE_INVALID
                ));
        return paymentMethodService.registerIssued(memberId, issueId, billingKey);
    }

    public PaymentMethodResponse complete(Long authenticatedMemberId, String issueId, String billingKey) {
        Long issuedMemberId = billingKeyIssueRepository.consume(issueId)
                .orElseThrow(() -> new ServiceException(
                        PaymentMethodErrorCode.BILLING_KEY_ISSUE_INVALID
                ));
        if (!issuedMemberId.equals(authenticatedMemberId)) {
            throw new ServiceException(PaymentMethodErrorCode.BILLING_KEY_ISSUE_INVALID);
        }
        return paymentMethodService.registerIssued(issuedMemberId, issueId, billingKey);
    }

    public void discard(String issueId) {
        billingKeyIssueRepository.discard(issueId);
    }
}
