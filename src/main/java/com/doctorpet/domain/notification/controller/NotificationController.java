package com.doctorpet.domain.notification.controller;

// 알림 조회·읽음 처리 API(SA §8-8). 수신자는 @AuthenticationPrincipal로만 식별하고 경로/쿼리의 memberId·hospitalId는
// 신뢰하지 않는다. 회원은 (MEMBER, memberId), 병원 스태프는 (HOSPITAL, hospitalId)로 해석해 자기 수신 알림만 접근한다.

import com.doctorpet.domain.notification.dto.response.NotificationDeleteAllResponse;
import com.doctorpet.domain.notification.dto.response.NotificationPageResponse;
import com.doctorpet.domain.notification.dto.response.NotificationReadAllResponse;
import com.doctorpet.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.doctorpet.domain.notification.service.NotificationRecipient;
import com.doctorpet.domain.notification.service.NotificationRecipientResolver;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final NotificationRecipientResolver recipientResolver;

    @GetMapping
    public ApiResponse<NotificationPageResponse> getMyNotifications(
            @AuthenticationPrincipal MemberPrincipal principal,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        NotificationRecipient recipient = recipientResolver.resolve(principal);
        return ApiResponse.success(
                notificationService.getMyNotifications(recipient, isRead, page, size)
        );
    }

    @GetMapping("/unread-count")
    public ApiResponse<NotificationUnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        NotificationRecipient recipient = recipientResolver.resolve(principal);
        return ApiResponse.success(notificationService.getUnreadCount(recipient));
    }

    @PatchMapping("/read-all")
    public ApiResponse<NotificationReadAllResponse> markAllRead(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        NotificationRecipient recipient = recipientResolver.resolve(principal);
        return ApiResponse.success(notificationService.markAllRead(recipient));
    }

    // 전체 삭제. 수신자의 알림을 하드 삭제한다(멱등). 병원 수신은 병원 단위 공유 삭제다(read-all과 동일 모델).
    @DeleteMapping
    public ApiResponse<NotificationDeleteAllResponse> deleteAll(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        NotificationRecipient recipient = recipientResolver.resolve(principal);
        return ApiResponse.success(notificationService.deleteAll(recipient));
    }

    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markAsRead(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long notificationId
    ) {
        NotificationRecipient recipient = recipientResolver.resolve(principal);
        notificationService.markAsRead(recipient, notificationId);
        return ApiResponse.success();
    }
}
