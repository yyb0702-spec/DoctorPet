package com.doctorpet.domain.notification.dto.response;

// 모두 읽음 처리 결과 응답. 이번 요청에서 미읽음→읽음으로 갱신된 건수를 내려준다(멱등이라 0건일 수 있다).

public record NotificationReadAllResponse(int updatedCount) {

    public static NotificationReadAllResponse of(int updatedCount) {
        return new NotificationReadAllResponse(updatedCount);
    }
}
