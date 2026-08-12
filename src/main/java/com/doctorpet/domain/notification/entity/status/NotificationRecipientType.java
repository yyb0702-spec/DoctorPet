package com.doctorpet.domain.notification.entity.status;

// 알림 수신자 유형(SA §4 notifications.recipient_type). 회원(보호자) 개인 수신 또는 병원 단위 수신을 구분한다(고도화 3.10).
// 병원 수신은 스태프 개인이 아니라 병원 1건으로 저장·읽음되는 "병원 단위 공유"다.
public enum NotificationRecipientType {
    MEMBER,
    HOSPITAL
}
