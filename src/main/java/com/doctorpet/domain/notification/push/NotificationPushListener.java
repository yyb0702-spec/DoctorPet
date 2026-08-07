package com.doctorpet.domain.notification.push;

// 알림 저장 트랜잭션이 커밋된 뒤에만 실시간 전송을 실행하는 리스너(SA §9-8 불변식).
// AFTER_COMMIT이라 롤백된 트랜잭션은 전송하지 않고, 전송 예외는 여기서 삼켜 커밋된 저장·상위 흐름에 전파하지 않는다.

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushListener {

    private final NotificationPusher pusher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        try {
            pusher.push(event.recipientMemberId(), event.payload());
        } catch (RuntimeException e) {
            // 전송 실패는 부가 채널의 문제일 뿐 저장은 이미 확정됐다. 보호자는 폴링으로 알림을 받을 수 있다.
            log.warn("실시간 알림 전송 실패 memberId={} notificationId={}",
                    event.recipientMemberId(), event.payload().id(), e);
        }
    }
}
