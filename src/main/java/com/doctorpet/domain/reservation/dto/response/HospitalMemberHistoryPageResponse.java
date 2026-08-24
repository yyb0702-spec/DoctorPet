// 병원 회원 이력 페이지 응답 — 프론트 PageResponse 계약과 동일(0-base page).
package com.doctorpet.domain.reservation.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

public record HospitalMemberHistoryPageResponse(
        List<HospitalMemberHistoryItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static HospitalMemberHistoryPageResponse from(
            Page<HospitalMemberHistoryItemResponse> result
    ) {
        return new HospitalMemberHistoryPageResponse(
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
