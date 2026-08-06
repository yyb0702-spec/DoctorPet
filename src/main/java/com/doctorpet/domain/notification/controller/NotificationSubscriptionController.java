package com.doctorpet.domain.notification.controller;

// 실시간 알림(SSE) 구독 API(SA §9-8). 티켓 발급은 JWT 인증으로, 구독(GET subscribe)은 EventSource가
// 헤더를 못 실으므로 발급받은 1회성 티켓으로 식별한다. 경로/쿼리의 memberId는 신뢰하지 않는다.

import com.doctorpet.domain.notification.dto.response.SubscribeTicketResponse;
import com.doctorpet.domain.notification.service.NotificationSubscriptionService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationSubscriptionController {

    private final NotificationSubscriptionService subscriptionService;

    // 구독 전 단기 티켓을 발급한다(JWT 인증 필요).
    @PostMapping("/subscribe-ticket")
    public ApiResponse<SubscribeTicketResponse> issueTicket(
            @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return ApiResponse.success(subscriptionService.issueTicket(principal.memberId()));
    }

    // 발급받은 티켓으로 SSE 스트림을 구독한다(비인증 경로, 티켓으로 식별).
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam @NotBlank String ticket) {
        return subscriptionService.subscribe(ticket);
    }
}
