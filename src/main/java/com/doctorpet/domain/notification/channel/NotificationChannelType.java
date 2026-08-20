package com.doctorpet.domain.notification.channel;

// 저장된 알림을 수신자에게 실제로 전달하는 채널 종류(고도화 3.9). 인앱 저장은 여기 없다 — 저장은 알림의 원본이고
// (NotificationService.create) 끌 수 있는 대상이 아니다. 이 enum은 "저장 뒤에 추가로 나가는 전달 수단"만 열거한다.
// 컬럼에 저장할 때는 VARCHAR로 못박는다(이슈 #176) — native ENUM이면 값 추가마다 마이그레이션이 필요해진다.
public enum NotificationChannelType {
    // 단방향 SSE 실시간 전송(SA §9-8). 기존 NotificationPusher·SseNotificationPusher를 그대로 쓴다.
    REALTIME,
    // 이메일. 앱이 열려 있지 않을 때 SSE가 못 메우는 공백을 메운다(고도화 3.9).
    EMAIL
}
