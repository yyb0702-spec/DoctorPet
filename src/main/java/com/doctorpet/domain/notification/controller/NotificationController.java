package com.doctorpet.domain.notification.controller;

// 알림 조회·읽음 처리 API(SA §8-8). 수신자는 @AuthenticationPrincipal로만 식별하고 경로/쿼리의 memberId는 신뢰하지 않는다.

import com.doctorpet.domain.notification.dto.response.NotificationPageResponse;
import com.doctorpet.domain.notification.dto.response.NotificationReadAllResponse;
import com.doctorpet.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<NotificationPageResponse> getMyNotifications(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(
                notificationService.getMyNotifications(principal.memberId(), isRead, page, size)
        );
    }

    @GetMapping("/unread-count")
    public ApiResponse<NotificationUnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return ApiResponse.success(notificationService.getUnreadCount(principal.memberId()));
    }

    @PatchMapping("/read-all")
    public ApiResponse<NotificationReadAllResponse> markAllRead(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return ApiResponse.success(notificationService.markAllRead(principal.memberId()));
    }

    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markAsRead(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long notificationId
    ) {
        notificationService.markAsRead(principal.memberId(), notificationId);
        return ApiResponse.success();
    }
}
