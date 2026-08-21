package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.payment.dto.request.PaymentMethodRegisterRequest;
import com.doctorpet.domain.payment.dto.response.PaymentMethodResponse;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import com.doctorpet.domain.payment.exception.PaymentMethodErrorCode;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 1 — 결제수단 서비스 단위 검증(Mockito). 게이트웨이·암호화·저장소는 목으로 대체한다.
 * 실제 DB 제약·매핑은 PaymentMethodDdlIntegrationTest(Level 3)에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentMethodServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private BillingKeyCryptor billingKeyCryptor;

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @InjectMocks
    private PaymentMethodService paymentMethodService;

    @Test
    @DisplayName("유효한 빌링키는 암호화되어 저장되고, 표시용 카드 정보만 응답한다")
    void register_success() {
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("valid_billing_key");
        given(paymentGateway.verifyBillingKey("valid_billing_key"))
                .willReturn(new BillingKeyIssueResult(true, "SHINHAN", "1234"));
        given(billingKeyCryptor.encrypt("valid_billing_key")).willReturn("v1:encrypted");
        given(paymentMethodRepository.save(any(PaymentMethod.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        PaymentMethodResponse response = paymentMethodService.register(MEMBER_ID, request);

        ArgumentCaptor<PaymentMethod> captor = ArgumentCaptor.forClass(PaymentMethod.class);
        verify(paymentMethodRepository).save(captor.capture());
        PaymentMethod saved = captor.getValue();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        // 원문이 아닌 암호문이 저장돼야 한다.
        assertThat(saved.getBillingKeyEnc()).isEqualTo("v1:encrypted");
        assertThat(saved.getStatus()).isEqualTo(PaymentMethodStatus.ACTIVE);
        assertThat(response.cardBrand()).isEqualTo("SHINHAN");
        assertThat(response.cardLast4()).isEqualTo("1234");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.isDefault()).isTrue();
    }

    @Test
    @DisplayName("서버 발급 issueId와 PortOne 응답 issueId가 일치할 때만 빌링키를 저장한다")
    void registerIssued_matchingMerchantId_savesBillingKey() {
        given(paymentGateway.verifyBillingKey("valid_billing_key"))
                .willReturn(new BillingKeyIssueResult(true, "SHINHAN", "1234", "issue-1"));
        given(billingKeyCryptor.encrypt("valid_billing_key")).willReturn("v1:encrypted");
        given(paymentMethodRepository.save(any(PaymentMethod.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        paymentMethodService.registerIssued(MEMBER_ID, "issue-1", "valid_billing_key");

        verify(paymentMethodRepository).save(any(PaymentMethod.class));
    }

    @Test
    @DisplayName("서버 발급 issueId와 PortOne 응답 issueId가 다르면 저장하지 않는다")
    void registerIssued_mismatchedMerchantId_rejects() {
        given(paymentGateway.verifyBillingKey("forged_key"))
                .willReturn(new BillingKeyIssueResult(true, "SHINHAN", "1234", "another-issue"));

        assertThatThrownBy(() -> paymentMethodService.registerIssued(MEMBER_ID, "issue-1", "forged_key"))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentMethodErrorCode.BILLING_KEY_ISSUE_MISMATCH);

        verify(billingKeyCryptor, never()).encrypt(anyString());
        verify(paymentMethodRepository, never()).save(any());
    }

    @Test
    @DisplayName("기존 활성 결제수단이 있고 기본값이 비어 있어도 신규 등록분을 기본값으로 만들지 않는다")
    void register_existingActivePaymentMethod_doesNotAssignDefault() {
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("valid_billing_key");
        given(paymentGateway.verifyBillingKey("valid_billing_key"))
                .willReturn(new BillingKeyIssueResult(true, "SHINHAN", "1234"));
        given(billingKeyCryptor.encrypt("valid_billing_key")).willReturn("v1:encrypted");
        given(paymentMethodRepository.existsByMemberIdAndStatus(MEMBER_ID, PaymentMethodStatus.ACTIVE))
                .willReturn(true);
        given(paymentMethodRepository.save(any(PaymentMethod.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        PaymentMethodResponse response = paymentMethodService.register(MEMBER_ID, request);

        ArgumentCaptor<PaymentMethod> captor = ArgumentCaptor.forClass(PaymentMethod.class);
        verify(paymentMethodRepository).save(captor.capture());
        assertThat(captor.getValue().isDefaultPaymentMethod()).isFalse();
        assertThat(response.isDefault()).isFalse();
    }

    @Test
    @DisplayName("등록 과정에서 결제 승인(approve)은 호출되지 않는다 — 금액 이동 없음 보장")
    void register_doesNotTriggerPayment() {
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("valid_billing_key");
        given(paymentGateway.verifyBillingKey(anyString()))
                .willReturn(new BillingKeyIssueResult(true, "KB", "5678"));
        given(billingKeyCryptor.encrypt(anyString())).willReturn("v1:enc");
        given(paymentMethodRepository.save(any(PaymentMethod.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        paymentMethodService.register(MEMBER_ID, request);

        verify(paymentGateway, never()).approve(any());
    }

    @Test
    @DisplayName("게이트웨이가 빌링키를 무효로 판정하면 INVALID_BILLING_KEY로 거부하고 저장하지 않는다")
    void register_invalidBillingKey() {
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("forged_key");
        given(paymentGateway.verifyBillingKey("forged_key"))
                .willReturn(new BillingKeyIssueResult(false, null, null));

        assertThatThrownBy(() -> paymentMethodService.register(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentMethodErrorCode.INVALID_BILLING_KEY);

        verify(billingKeyCryptor, never()).encrypt(anyString());
        verify(paymentMethodRepository, never()).save(any());
    }

    @Test
    @DisplayName("게이트웨이 호출이 실패하면 BILLING_KEY_VERIFICATION_FAILED로 응답한다")
    void register_gatewayFailure() {
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("some_key");
        given(paymentGateway.verifyBillingKey("some_key"))
                .willThrow(new PaymentGatewayException(GatewayFailureReason.RETRIABLE, "PG-timeout", "timeout"));

        assertThatThrownBy(() -> paymentMethodService.register(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentMethodErrorCode.BILLING_KEY_VERIFICATION_FAILED);

        verify(paymentMethodRepository, never()).save(any());
    }

    @Test
    @DisplayName("MVP는 중복 등록을 허용한다 — 같은 빌링키를 다시 등록해도 거부 없이 새로 저장한다")
    void register_allowsDuplicate() {
        PaymentMethodRegisterRequest request = new PaymentMethodRegisterRequest("valid_billing_key");
        given(paymentGateway.verifyBillingKey("valid_billing_key"))
                .willReturn(new BillingKeyIssueResult(true, "SHINHAN", "1234"));
        given(billingKeyCryptor.encrypt("valid_billing_key")).willReturn("v1:encrypted");
        given(paymentMethodRepository.existsByMemberIdAndStatus(MEMBER_ID, PaymentMethodStatus.ACTIVE))
                .willReturn(false, true);
        given(paymentMethodRepository.save(any(PaymentMethod.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // 같은 회원이 같은 빌링키로 두 번 등록해도 예외 없이 각각 저장된다(중복 검사 없음 = MVP 정책, SA §8-7).
        paymentMethodService.register(MEMBER_ID, request);
        paymentMethodService.register(MEMBER_ID, request);

        verify(paymentMethodRepository, times(2)).save(any(PaymentMethod.class));
    }

    @Test
    @DisplayName("본인 결제수단 조회는 ACTIVE 목록만 표시용으로 반환한다")
    void getMyPaymentMethods_returnsActiveOnly() {
        given(paymentMethodRepository.findByMemberIdAndStatusOrderByCreatedAtDesc(MEMBER_ID, PaymentMethodStatus.ACTIVE))
                .willReturn(List.of(
                        PaymentMethod.issue(MEMBER_ID, "v1:enc1", "SHINHAN", "1111"),
                        PaymentMethod.issue(MEMBER_ID, "v1:enc2", "KB", "2222")));

        List<PaymentMethodResponse> responses = paymentMethodService.getMyPaymentMethods(MEMBER_ID);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(PaymentMethodResponse::cardLast4).containsExactly("1111", "2222");
    }

    @Test
    @DisplayName("예약에 사용할 결제수단은 본인 소유의 ACTIVE 상태인지 확인한다")
    void isActiveAndOwnedBy_checksOwnerAndStatus() {
        given(paymentMethodRepository.existsByIdAndMemberIdAndStatus(
                10L,
                MEMBER_ID,
                PaymentMethodStatus.ACTIVE
        )).willReturn(true);

        assertThat(paymentMethodService.isActiveAndOwnedBy(
                MEMBER_ID,
                10L
        )).isTrue();
    }

    @Test
    @DisplayName("본인 소유 결제수단 삭제는 소프트 삭제(status=DELETED)로 처리한다")
    void delete_ownedIsSoftDeleted() {
        PaymentMethod paymentMethod = PaymentMethod.issue(MEMBER_ID, "v1:enc", "SHINHAN", "1234");
        given(paymentMethodRepository.findByIdAndMemberId(10L, MEMBER_ID))
                .willReturn(Optional.of(paymentMethod));

        paymentMethodService.delete(MEMBER_ID, 10L);

        assertThat(paymentMethod.getStatus()).isEqualTo(PaymentMethodStatus.DELETED);
    }

    @Test
    @DisplayName("존재하지 않거나 타인 소유 결제수단 삭제는 PAYMENT_METHOD_NOT_FOUND로 응답한다")
    void delete_notOwned() {
        given(paymentMethodRepository.findByIdAndMemberId(99L, MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentMethodService.delete(MEMBER_ID, 99L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND);
    }

    @Test
    @DisplayName("기본 결제수단을 변경하면 기존 기본값은 해제되고 대상만 기본값이 된다")
    void setDefault_changesOnlyTarget() {
        PaymentMethod previous = paymentMethod(10L, "1111");
        previous.markDefault();
        PaymentMethod target = paymentMethod(11L, "2222");
        given(paymentMethodRepository.findActiveByMemberIdForUpdate(MEMBER_ID))
                .willReturn(List.of(previous, target));

        PaymentMethodResponse response = paymentMethodService.setDefault(MEMBER_ID, 11L);

        assertThat(previous.isDefaultPaymentMethod()).isFalse();
        assertThat(target.isDefaultPaymentMethod()).isTrue();
        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.isDefault()).isTrue();
    }

    @Test
    @DisplayName("삭제된 결제수단은 기본값으로 지정할 수 없다")
    void setDefault_deletedPaymentMethod_throws() {
        PaymentMethod deleted = paymentMethod(10L, "1111");
        deleted.markDeleted();
        given(paymentMethodRepository.findActiveByMemberIdForUpdate(MEMBER_ID)).willReturn(List.of());
        given(paymentMethodRepository.findByIdAndMemberId(10L, MEMBER_ID)).willReturn(Optional.of(deleted));

        assertThatThrownBy(() -> paymentMethodService.setDefault(MEMBER_ID, 10L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_ACTIVE);
    }

    private PaymentMethod paymentMethod(Long id, String last4) {
        PaymentMethod paymentMethod = PaymentMethod.issue(MEMBER_ID, "v1:" + id, "SHINHAN", last4);
        ReflectionTestUtils.setField(paymentMethod, "id", id);
        return paymentMethod;
    }
}
