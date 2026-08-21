package com.doctorpet.domain.notification.entity.status;

// 저장된 알림을 수신자에게 실제로 전달하는 채널 종류(고도화 3.9). 인앱 저장은 여기 없다 — 저장은 알림의 원본이고
// (NotificationService.create) 끌 수 있는 대상이 아니다. 이 enum은 "저장 뒤에 추가로 나가는 전달 수단"만 열거한다.
// notification_preferences.channel에 저장되므로 다른 저장형 enum과 같은 entity/status 패키지에 둔다 — channel
// 패키지에 두면 entity가 channel을 참조하고 channel이 entity를 참조하는 순환이 생긴다(리뷰 지적 P2).
// 컬럼에 저장할 때는 VARCHAR로 못박는다(이슈 #176) — native ENUM이면 값 추가마다 마이그레이션이 필요해진다.
public enum NotificationChannelType {
    // 단방향 SSE 실시간 전송(SA §9-8). 기존 NotificationPusher·SseNotificationPusher를 그대로 쓴다.
    // 수신 설정 대상이 아니다(configurable=false) — 인앱 화면을 실시간으로 갱신하는 수단이라 끄는 개념이 없다.
    REALTIME(false),
    // 이메일. 앱이 열려 있지 않을 때 SSE가 못 메우는 공백을 메운다(고도화 3.9). 회원이 유형별로 끌 수 있다.
    EMAIL(true);

    // 회원이 수신 여부를 고를 수 있는 채널인지. notification_preferences 행을 만들 수 있는지의 기준이다
    // (리뷰 지적 P2 — 표가 받아주지만 아무도 읽지 않는 설정이 생기는 것을 막는다). 새 채널을 추가하는 사람은
    // 이 값을 반드시 정해야 하고, true로 두면 그 채널이 설정을 읽는 코드도 함께 있어야 한다.
    private final boolean configurable;

    NotificationChannelType(boolean configurable) {
        this.configurable = configurable;
    }

    public boolean isConfigurable() {
        return configurable;
    }
}
