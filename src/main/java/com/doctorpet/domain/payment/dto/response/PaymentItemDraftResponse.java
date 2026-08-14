package com.doctorpet.domain.payment.dto.response;

import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.service.PaymentItemDraftToken;
import java.util.List;

/*
  청구 항목 초안 조회·저장 응답(SA §8-7). 항목 목록과 함께 낙관적 검증 토큰을 내려준다.
  클라이언트는 이 토큰을 청구 요청에 그대로 실어 보내며, 서버는 잠금 아래에서 다시 계산해 대조한다
  — 두 요청 사이에 다른 스태프가 초안을 바꿨으면 청구를 거부한다(SA §9-4 "초안 교체 경합").
 */
public record PaymentItemDraftResponse(
        List<PaymentItemResponse> items,
        String draftToken
) {

    public static PaymentItemDraftResponse from(List<PaymentItem> drafts) {
        return new PaymentItemDraftResponse(
                PaymentItemResponse.from(drafts),
                PaymentItemDraftToken.of(drafts)
        );
    }
}
