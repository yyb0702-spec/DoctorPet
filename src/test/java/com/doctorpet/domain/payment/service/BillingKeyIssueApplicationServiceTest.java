package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.doctorpet.domain.payment.exception.PaymentMethodErrorCode;
import com.doctorpet.domain.payment.repository.BillingKeyIssueRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Level 1 — 발급 시도의 회원 귀속·단발 소비를 서비스 경계에서 검증한다. */
@ExtendWith(MockitoExtension.class)
class BillingKeyIssueApplicationServiceTest {

    @Mock
    private BillingKeyIssueRepository billingKeyIssueRepository;

    @Mock
    private PaymentMethodService paymentMethodService;

    @InjectMocks
    private BillingKeyIssueApplicationService service;

    @Test
    @DisplayName("iframe 완료는 발급 회원과 일치할 때만 등록 서비스로 넘긴다")
    void complete_authenticatedMemberMatches_registers() {
        when(billingKeyIssueRepository.consume("issue-1")).thenReturn(Optional.of(1L));

        service.complete(1L, "issue-1", "billing-key");

        verify(paymentMethodService).registerIssued(1L, "issue-1", "billing-key");
    }

    @Test
    @DisplayName("다른 회원이 issueId를 완료하려 하면 소비 후 등록하지 않고 거부한다")
    void complete_authenticatedMemberMismatch_rejects() {
        when(billingKeyIssueRepository.consume("issue-1")).thenReturn(Optional.of(1L));

        assertThatThrownBy(() -> service.complete(2L, "issue-1", "billing-key"))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentMethodErrorCode.BILLING_KEY_ISSUE_INVALID);

        verify(paymentMethodService, never()).registerIssued(1L, "issue-1", "billing-key");
    }
}
