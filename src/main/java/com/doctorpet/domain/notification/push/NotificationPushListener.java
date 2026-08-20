package com.doctorpet.domain.notification.push;

// 알림 저장 트랜잭션이 커밋된 뒤에만 채널 전달을 실행하는 리스너(SA §9-8 불변식).
// AFTER_COMMIT이라 롤백된 트랜잭션은 전달하지 않고, 전달 예외는 여기서 채널별로 삼켜 커밋된 저장·상위 흐름에
// 전파하지 않는다(고도화 3.9 — 채널이 둘 이상이 되면서 격리 단위를 채널로 좁혔다).

import com.doctorpet.domain.notification.channel.NotificationChannel;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPushListener {

    // 등록된 모든 전달 채널(SSE·이메일). 대상이 아닌 알림은 각 채널이 조용히 no-op한다.
    private final List<NotificationChannel> channels;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        for (NotificationChannel channel : channels) {
            try {
                channel.deliver(event.recipientType(), event.recipientId(), event.type(), event.payload());
            } catch (RuntimeException e) {
                // 채널 하나의 실패가 나머지 채널을 삼키지 않도록 루프 안에서 잡는다 — 이메일 발송(SMTP 장애)이
                // SSE 전달을 막거나 그 반대가 되면, 저장은 됐는데 도달 가능한 채널까지 함께 잃는다.
                // 전달 실패는 부가 채널의 문제일 뿐 저장은 이미 확정됐다. 수신자는 폴링으로 알림을 받을 수 있다.
                log.warn("알림 채널 전달 실패 channel={} recipientType={} recipientId={} notificationId={}",
                        channel.type(), event.recipientType(), event.recipientId(), event.payload().id(), e);
            }
        }
    }
}
