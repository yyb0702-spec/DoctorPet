package com.doctorpet.domain.chat.controller;

import com.doctorpet.domain.chat.dto.request.ChatReadRequest;
import com.doctorpet.domain.chat.dto.response.ChatMessagePageResponse;
import com.doctorpet.domain.chat.service.ChatMessageService;
import com.doctorpet.global.response.ApiResponse;
import com.doctorpet.global.security.MemberPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    @GetMapping("/{reservationId}/chat/messages")
    public ApiResponse<ChatMessagePageResponse> getMessages(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId,
            @RequestParam(required = false) @Positive Long afterMessageId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(chatMessageService.getMessages(
                reservationId, principal, afterMessageId, size));
    }

    @PatchMapping("/{reservationId}/chat/messages/read")
    public ApiResponse<Void> markRead(
            @AuthenticationPrincipal MemberPrincipal principal,
            @PathVariable @Positive Long reservationId,
            @Valid @RequestBody ChatReadRequest request
    ) {
        chatMessageService.markRead(reservationId, principal, request.throughMessageId());
        return ApiResponse.success();
    }
}
