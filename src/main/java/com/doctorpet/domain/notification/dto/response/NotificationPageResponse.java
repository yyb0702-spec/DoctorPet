package com.doctorpet.domain.notification.dto.response;

// 알림 목록 페이징 응답. 기존 도메인(ReservationPageResponse)과 동일한 페이지 메타 형태를 따른다.

import org.springframework.data.domain.Page;

import java.util.List;

public record NotificationPageResponse(
        List<NotificationResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {

    public static NotificationPageResponse from(Page<NotificationResponse> result) {
        return new NotificationPageResponse(
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
