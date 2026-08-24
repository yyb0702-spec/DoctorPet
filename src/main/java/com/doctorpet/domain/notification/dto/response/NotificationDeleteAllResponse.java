package com.doctorpet.domain.notification.dto.response;

// 전체 삭제 결과 응답. 이번 요청에서 하드 삭제된 알림 건수를 내려준다(멱등이라 0건일 수 있다).

public record NotificationDeleteAllResponse(int deletedCount) {

    public static NotificationDeleteAllResponse of(int deletedCount) {
        return new NotificationDeleteAllResponse(deletedCount);
    }
}
