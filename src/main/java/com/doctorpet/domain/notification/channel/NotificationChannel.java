package com.doctorpet.domain.notification.channel;

// 저장된 알림을 수신자에게 전달하는 채널 추상화(고도화 3.9). 채널을 추가할 때 발행부·저장부를 건드리지 않게 한다.

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationChannelType;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationType;

/**
 * 알림 전달 채널. 구현체는 {@code NotificationPushListener}가 알림 저장 트랜잭션의 <b>커밋 이후</b>에만 호출한다
 * (SA §9-8 불변식) — 채널 구현은 절대 트랜잭션 안에서 외부 호출을 하지 않는다.
 *
 * <p>저장(인앱)은 채널이 아니다. 저장이 알림의 원본이고 전달은 부가 수단이므로, 전달 실패는 저장·상위 도메인
 * 트랜잭션에 영향을 주지 않고 리스너가 채널별로 격리해 삼킨다(한 채널의 실패가 다른 채널을 막지 않는다).
 *
 * <p>전달 여부 판단(수신자 유형·알림 유형·회원 수신 설정)은 각 구현체의 책임이다 — 리스너는 모든 채널에 넘기고,
 * 자기 대상이 아니면 구현체가 조용히 no-op한다. 채널마다 대상 기준이 다르기 때문이다(예: 이메일은 회원 수신의
 * 예약 승인·결제 결과만).
 */
public interface NotificationChannel {

    /**
     * 채널 호출 순서. {@code NotificationPushListener}가 이 순서대로 <b>동기</b> 호출하므로, 외부 왕복이 있는
     * 채널은 반드시 뒤에 둔다 — 앞에 두면 그 왕복이 끝날 때까지 뒤 채널의 전달과 요청 응답이 함께 막힌다
     * (리뷰 지적 P1). 순서를 명시하지 않으면 빈 등록 순서(클래스명 알파벳)에 좌우되는데, 그건 결정이 아니다.
     * 낮은 값이 먼저 호출된다. 새 채널은 외부 호출 여부를 기준으로 값을 고른다.
     */
    int REALTIME_ORDER = 0;

    int EMAIL_ORDER = 100;

    NotificationChannelType type();

    void deliver(
            NotificationRecipientType recipientType,
            Long recipientId,
            NotificationType type,
            NotificationResponse notification
    );
}
