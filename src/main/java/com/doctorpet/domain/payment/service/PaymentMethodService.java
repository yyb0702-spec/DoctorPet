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
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/*
  보호자 결제수단 등록·조회·삭제(SA §8-7, 이슈 #33).
  게이트웨이 호출(외부 I/O)은 트랜잭션 밖에서 수행하고, DB 쓰기만 트랜잭션 경계에 둔다(구현 가드레일).
  등록 단계에서는 결제 승인(approve)을 호출하지 않아 금액 이동이 없다.
 */
@Service
@RequiredArgsConstructor
public class PaymentMethodService {

    private static final String ACTIVE_DEFAULT_UNIQUE_CONSTRAINT =
            "uk_payment_methods_active_default_member_id";

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

        return saveVerifiedBillingKey(memberId, request.billingKey(), result);
    }

    /**
     * PortOne 모바일 콜백의 빌링키를 등록한다. 브라우저가 보낸 issueId를 믿지 않고, 서버가
     * PortOne 단건 조회에서 받은 merchantId가 발급 때 서버가 만든 issueId와 같은지 확인한다.
     */
    public PaymentMethodResponse registerIssued(Long memberId, String issueId, String billingKey) {
        BillingKeyIssueResult result = verifyBillingKey(billingKey);
        if (!result.valid()) {
            throw new ServiceException(PaymentMethodErrorCode.INVALID_BILLING_KEY);
        }
        if (!issueId.equals(result.merchantId())) {
            throw new ServiceException(PaymentMethodErrorCode.BILLING_KEY_ISSUE_MISMATCH);
        }

        return saveVerifiedBillingKey(memberId, billingKey, result);
    }

    private PaymentMethodResponse saveVerifiedBillingKey(
            Long memberId,
            String billingKey,
            BillingKeyIssueResult result
    ) {
        String billingKeyEnc = billingKeyCryptor.encrypt(billingKey);
        PaymentMethod saved = saveAsFirstDefaultOrNonDefault(
                memberId,
                billingKeyEnc,
                result
        );
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

    /** 예약 결제수단 재지정용 소유권·활성 상태 검증이다. */
    @Transactional(readOnly = true)
    public void assertActiveAndOwnedBy(Long memberId, Long paymentMethodId) {
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndMemberId(paymentMethodId, memberId)
                .orElseThrow(() -> new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND));
        if (paymentMethod.getStatus() != PaymentMethodStatus.ACTIVE) {
            throw new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_ACTIVE);
        }
    }

    /**
     * 회원의 ACTIVE 결제수단 행을 잠가 기본값 변경을 직렬화한다. DB 생성 컬럼 UNIQUE가 최종 방어선이고,
     * 행 잠금은 정상 동시 요청을 재시도·500 없이 순차 적용하기 위한 상위 보호다.
     */
    @Transactional
    public PaymentMethodResponse setDefault(Long memberId, Long paymentMethodId) {
        List<PaymentMethod> activeMethods = paymentMethodRepository.findActiveByMemberIdForUpdate(memberId);
        PaymentMethod target = activeMethods.stream()
                .filter(paymentMethod -> paymentMethod.getId().equals(paymentMethodId))
                .findFirst()
                .orElseGet(() -> requireActiveOwnedPaymentMethod(memberId, paymentMethodId));

        for (PaymentMethod paymentMethod : activeMethods) {
            if (!paymentMethod.getId().equals(target.getId())) {
                paymentMethod.clearDefault();
            }
        }
        // Hibernate의 UPDATE 실행 순서는 엔티티 목록 순서와 무관하다. 기존 기본값 해제와 새 기본값 지정을
        // 한 flush에 섞으면 새 행 UPDATE가 먼저 실행돼 생성 컬럼 UNIQUE를 일시적으로 위반할 수 있으므로,
        // 해제를 먼저 DB에 반영한 뒤 대상만 지정한다.
        paymentMethodRepository.flush();
        target.markDefault();
        return PaymentMethodResponse.from(target);
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

    private PaymentMethod saveAsFirstDefaultOrNonDefault(
            Long memberId,
            String billingKeyEnc,
            BillingKeyIssueResult result
    ) {
        // 기존 활성 결제수단이 있으나 기본값이 비어 있는 레거시 회원에게 신규 카드를 기본값으로
        // 부여하지 않는다. 기본값의 부재가 "첫 등록"을 뜻하지는 않는다.
        if (paymentMethodRepository.existsByMemberIdAndStatus(memberId, PaymentMethodStatus.ACTIVE)) {
            return paymentMethodRepository.save(PaymentMethod.issue(
                    memberId,
                    billingKeyEnc,
                    result.cardBrand(),
                    result.cardLast4()
            ));
        }
        try {
            return paymentMethodRepository.save(PaymentMethod.issueAsDefault(
                    memberId,
                    billingKeyEnc,
                    result.cardBrand(),
                    result.cardLast4()
            ));
        } catch (DataIntegrityViolationException exception) {
            if (!isActiveDefaultDuplicate(exception)) {
                throw exception;
            }
            return paymentMethodRepository.save(PaymentMethod.issue(
                    memberId,
                    billingKeyEnc,
                    result.cardBrand(),
                    result.cardLast4()
            ));
        }
    }

    private PaymentMethod requireActiveOwnedPaymentMethod(Long memberId, Long paymentMethodId) {
        PaymentMethod paymentMethod = paymentMethodRepository
                .findByIdAndMemberId(paymentMethodId, memberId)
                .orElseThrow(() -> new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_FOUND));
        if (paymentMethod.getStatus() != PaymentMethodStatus.ACTIVE) {
            throw new ServiceException(PaymentMethodErrorCode.PAYMENT_METHOD_NOT_ACTIVE);
        }
        return paymentMethod;
    }

    private boolean isActiveDefaultDuplicate(DataIntegrityViolationException exception) {
        if (!(exception.getMostSpecificCause() instanceof SQLException sqlException)) {
            return false;
        }
        String message = sqlException.getMessage();
        return "23000".equals(sqlException.getSQLState())
                && sqlException.getErrorCode() == 1062
                && message != null
                && message.toLowerCase(Locale.ROOT).contains(ACTIVE_DEFAULT_UNIQUE_CONSTRAINT);
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
