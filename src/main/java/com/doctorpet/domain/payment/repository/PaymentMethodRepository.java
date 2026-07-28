package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.entity.PaymentMethodStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/*
  결제수단 조회. 소유권 조회(findByIdAndMemberId)는 #34 청구 시점에도 재사용한다.
 */
public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    // 본인 결제수단 목록. 삭제(DELETED)·만료 제외를 위해 상태로 필터한다.
    List<PaymentMethod> findByMemberIdAndStatusOrderByCreatedAtDesc(Long memberId, PaymentMethodStatus status);

    // 소유권 검증 겸 단건 로드. 상태와 무관하게 로드해 #34가 status==ACTIVE 여부를 직접 판단하게 한다.
    Optional<PaymentMethod> findByIdAndMemberId(Long id, Long memberId);
}
