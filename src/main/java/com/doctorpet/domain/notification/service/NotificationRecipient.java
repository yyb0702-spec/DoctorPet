package com.doctorpet.domain.notification.service;

// 알림 수신자 식별값(타입 + id). 회원이면 (MEMBER, memberId), 병원이면 (HOSPITAL, hospitalId)를 담는다(고도화 3.10).
// 저장·조회·읽음의 수신자 기준을 이 한 쌍으로 통일해, member_id 단일 축이던 로직을 수신자 인식형으로 확장한다.

import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;

public record NotificationRecipient(
        NotificationRecipientType type,
        Long id
) {

    public static NotificationRecipient member(Long memberId) {
        return new NotificationRecipient(NotificationRecipientType.MEMBER, memberId);
    }

    public static NotificationRecipient hospital(Long hospitalId) {
        return new NotificationRecipient(NotificationRecipientType.HOSPITAL, hospitalId);
    }
}
