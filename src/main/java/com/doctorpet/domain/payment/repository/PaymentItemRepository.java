package com.doctorpet.domain.payment.repository;

import com.doctorpet.domain.payment.entity.PaymentItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
  청구 항목 조회·초안 쓰기(SA §4 payment_items, §9-4 "청구 항목").

  쓰기는 모두 `payment_id IS NULL` 조건부다 — 스탬프된 항목은 조건이 성립하지 않아 0건이 되므로, 청구가 먼저
  커밋된 뒤 도착한 초안 수정·삭제가 이미 청구된 스냅샷을 건드릴 수 없다. 직렬화의 1차 방어선은 예약 행
  PESSIMISTIC_WRITE 락이고(청구 선기록이 이미 쓰는 락), 이 조건이 2차 방어선이다.

  항목화 도입 이전에 청구된 결제는 행이 없어 빈 리스트가 나온다 — 영수증은 이를 정상 상태로 다루고 백필하지 않는다.
 */
public interface PaymentItemRepository extends JpaRepository<PaymentItem, Long> {

    /** 영수증·검증이 입력 순서를 그대로 보여주도록 저장 순서(id)로 정렬해 반환한다. */
    List<PaymentItem> findByPaymentIdOrderByIdAsc(Long paymentId);

    /** 아직 청구되지 않은 초안 항목. 청구 선기록이 예약 행 락을 잡은 뒤 이 조회로 총액 산출 대상을 확정한다. */
    List<PaymentItem> findByReservationIdAndPaymentIdIsNullOrderByIdAsc(Long reservationId);

    /**
     * 초안 전체 교체의 삭제 단계. `payment_id IS NULL` 조건이라 스탬프된 항목은 지워지지 않는다.
     * 반환값은 지운 초안 수이며, 초안이 없던 예약에서는 0이다(이 값만으로 "이미 청구됨"을 판정하지 않는다 —
     * 그 판정은 결제 존재 여부로 하고, 이 조건은 스탬프된 항목 보호가 목적이다).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from PaymentItem i
             where i.reservationId = :reservationId
               and i.paymentId is null
            """)
    int deleteDraftsByReservationId(@Param("reservationId") Long reservationId);

    /**
     * 청구 선기록이 초안에 소속 결제를 스탬프한다(SA §4 — 청구 시점 스냅샷 고정).
     * `payment_id IS NULL` 조건부라 이미 스탬프된 항목을 다른 결제로 옮기지 않으며, 갱신 건수를 호출부가
     * 초안 수와 대조해 락 밖 경합으로 초안이 바뀐 경우를 잡아낸다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentItem i
               set i.paymentId = :paymentId
             where i.reservationId = :reservationId
               and i.paymentId is null
            """)
    int stampDraftsToPayment(
            @Param("reservationId") Long reservationId,
            @Param("paymentId") Long paymentId
    );
}
