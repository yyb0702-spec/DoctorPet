package com.doctorpet.domain.notification.dto.response;

// 미읽음 알림 개수 응답. 배지 숫자 하나만 필요할 때 목록 전체를 폴링하지 않고 개수만 내려준다.

public record NotificationUnreadCountResponse(long unreadCount) {

    public static NotificationUnreadCountResponse of(long unreadCount) {
        return new NotificationUnreadCountResponse(unreadCount);
    }
}
