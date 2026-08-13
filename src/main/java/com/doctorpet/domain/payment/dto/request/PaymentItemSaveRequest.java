package com.doctorpet.domain.payment.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/*
  청구 항목 초안 전체 교체 요청(SA §9-4 청구 항목). 병원 스태프가 진료 완료 후 청구 전까지 항목을 작성·수정한다.
  - 부분 수정(개별 PATCH·DELETE)이 아니라 전체 교체다. 스태프가 화면에서 최종 항목 목록을 확정해 보내는 흐름이고,
    전체 교체는 삭제·수정·추가를 한 번의 조건부 쓰기(WHERE payment_id IS NULL)로 처리해 청구와의 경합 지점이
    하나로 모인다(병원 진료역량 전체 교체 API와 같은 패턴).
  - 총액(amount)은 받지 않는다. 서버가 항목 합계로 산출한다(SA §9-4 — 클라이언트 결과를 믿지 않는다).
  - 항목은 최소 1개여야 한다(@NotEmpty → VALIDATION_FAILED 400). 합계 0 이하·상한 초과는 Service가
    INVALID_AMOUNT로 처리한다.
 */
public record PaymentItemSaveRequest(
        @NotEmpty(message = "청구 항목을 1개 이상 입력해주세요.")
        @Valid
        List<PaymentItemRequest> items
) {
}
