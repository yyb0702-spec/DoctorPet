package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.PaymentItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/*
  청구 항목 스냅샷 조회(고도화 결제 3.1). 항목은 청구 시작 트랜잭션에서만 저장되고 이후 수정되지 않으므로
  조회와 저장만 있고 갱신 메서드는 두지 않는다.
  항목 도입 이전에 청구된 과거 결제는 행이 없어 빈 리스트가 나온다 — 영수증은 이를 정상 상태로 다룬다.
 */
public interface PaymentItemRepository extends JpaRepository<PaymentItem, Long> {

    /** 영수증·검증이 입력 순서를 그대로 보여주도록 저장 순서(id)로 정렬해 반환한다. */
    List<PaymentItem> findByPaymentIdOrderByIdAsc(Long paymentId);
}
