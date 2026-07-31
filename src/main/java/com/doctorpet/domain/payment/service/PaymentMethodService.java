package com.doctorpet.domain.payment.service;

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
import com.doctorpet.global.gateway.payment.dto.BillingKeyIssueResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  보호자 결제수단 등록·조회·삭제(SA §8-7, 이슈 #33).
  게이트웨이 호출(외부 I/O)은 트랜잭션 밖에서 수행하고, DB 쓰기만 트랜잭션 경계에 둔다(구현 가드레일).
  등록 단계에서는 결제 승인(approve)을 호출하지 않아 금액 이동이 없다.
 */
@Service
@RequiredArgsConstructor
public class PaymentMethodService {

    private final PaymentGateway paymentGateway;
    private final BillingKeyCryptor billingKeyCryptor;
    private final PaymentMethodRepository paymentMethodRepository;

    /**
     * 빌링키를 게이트웨이로 검증한 뒤 암호화해 저장한다. 외부 호출을 트랜잭션 밖에 두기 위해
     * 이 메서드에는 @Transactional을 걸지 않는다(단일 save는 자체 트랜잭션으로 원자적이다).
     */
    public PaymentMethodResponse register(Long memberId, PaymentMethodRegisterRequest request) {
        BillingKeyIssueResult result = verifyBillingKey(request.billingKey());
        if (!result.valid()) {
            throw new ServiceException(PaymentMethodErrorCode.INVALID_BILLING_KEY);
        }

        String billingKeyEnc = billingKeyCryptor.encrypt(request.billingKey());
        PaymentMethod saved = paymentMethodRepository.save(
                PaymentMethod.issue(memberId, billingKeyEnc, result.cardBrand(), result.cardLast4()));
        return PaymentMethodResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> getMyPaymentMethods(Long memberId) {
        return paymentMethodRepository
                .findByMemberIdAndStatusOrderByCreatedAtDesc(memberId, PaymentMethodStatus.ACTIVE)
                .stream()
                .map(PaymentMethodResponse::from)
                .toList();
    }

    /**
     * 예약 요청이 인증 회원 소유의 활성 결제수단인지 확인할 때 사용하는 도메인 간 조회 계약.
     * 빌링키 암호문이나 카드 원본은 반환하지 않는다.
     */
    @Transactional(readOnly = true)
    public boolean isActiveAndOwnedBy(Long memberId, Long paymentMethodId) {
        return paymentMethodRepository.existsByIdAndMemberIdAndStatus(
                paymentMethodId,
                memberId,
                PaymentMethodStatus.ACTIVE
        );
    }

    /**
     * 소프트 삭제. 진행 중 예약이 참조하더라도 삭제를 허용한다(SA §4-2).
     * 청구 시점의 status 재확인·OFFLINE_REQUIRED 전이는 #34의 책임이다.
     */
    @Transactional
    public void delete(Long memberId, Long paymentMethodId) {
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndMemberId(paymentMethodId, memberId)
                .orElseThrow(() -> new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND));
        paymentMethod.markDeleted();
    }

    private BillingKeyIssueResult verifyBillingKey(String billingKey) {
        try {
            return paymentGateway.verifyBillingKey(billingKey);
        } catch (PaymentGatewayException e) {
            // 원문 빌링키를 로그·예외 메시지에 남기지 않는다(AGENTS 보안).
            throw new ServiceException(PaymentMethodErrorCode.BILLING_KEY_VERIFICATION_FAILED);
        }
    }
}
