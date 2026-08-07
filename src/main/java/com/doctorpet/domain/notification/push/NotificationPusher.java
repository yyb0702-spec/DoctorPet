package com.doctorpet.domain.notification.push;

import com.doctorpet.domain.notification.dto.response.NotificationResponse;

/**
 * 저장된 알림을 수신자에게 실시간 전달하는 추상화(SA §9-8). MVP는 폴링으로 완성했고, 이 계약은 push 채널을
 * 저장 흐름과 분리해 두어 구현체(SSE/WebSocket 등)를 교체 가능하게 한다.
 *
 * <p>전송은 알림 저장 트랜잭션이 <b>커밋된 이후</b>에만 호출한다({@link NotificationPushListener}).
 * 저장이 알림의 원본이고 전송은 부가 채널이므로, 전송 실패는 저장·상위 도메인 트랜잭션에 영향을 주지 않는다.
 */
public interface NotificationPusher {

    void push(Long recipientMemberId, NotificationResponse notification);
}
