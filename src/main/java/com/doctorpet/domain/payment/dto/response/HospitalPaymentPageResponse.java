// 병원 결제 목록 페이지 응답 — 프론트 PageResponse 계약과 동일(0-base page).
package com.doctorpet.domain.payment.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record HospitalPaymentPageResponse(
        List<HospitalPaymentListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static HospitalPaymentPageResponse from(
            Page<HospitalPaymentListItemResponse> result
    ) {
        return new HospitalPaymentPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.isFirst(),
                result.isLast()
        );
    }
}
