package com.doctorpet.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Level 1 — 수신자 유형별 SSE 라우팅 규칙을 고정한다. 회원 수신은 그 memberId 연결로만, 병원 수신은 발송
 * 시점에 그 병원에 소속된 스태프의 연결로 fan-out한다(고도화 3.10). 실제 소속 필터링(탈퇴·소속 해제·타 병원)이
 * 쿼리에서 성립하는지는 {@link NotificationHospitalFanOutIntegrationTest}가 실제 MySQL로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SseNotificationPusherTest {

    private static final Long MEMBER_ID = 11L;
    private static final Long HOSPITAL_ID = 500L;
    private static final String EVENT_NAME = "notification";

    @Mock
    private SseEmitterRegistry registry;

    @Mock
    private MemberService memberService;

    @InjectMocks
    private SseNotificationPusher pusher;

    private final NotificationResponse payload = new NotificationResponse(
            7L, "RESERVATION_CONFIRMED", "예약이 승인되었습니다.", "RESERVATION", 3L,
            false, null, LocalDateTime.of(2026, 8, 14, 10, 0));

    @Test
    @DisplayName("회원 수신 알림은 그 memberId 연결로만 전송하고 병원 소속 조회를 하지 않는다")
    void memberRecipient_sendsToThatMemberOnly() {
        pusher.push(NotificationRecipientType.MEMBER, MEMBER_ID, payload);

        verify(registry).send(MEMBER_ID, EVENT_NAME, payload);
        verifyNoMoreInteractions(registry);
        // 회원 경로는 기존 그대로다 — 소속 조회가 끼어들면 안 된다.
        verifyNoInteractions(memberService);
    }

    @Test
    @DisplayName("병원 수신 알림은 현재 소속 스태프 각각의 연결로 같은 payload를 fan-out한다")
    void hospitalRecipient_fansOutToCurrentStaff() {
        given(memberService.findActiveHospitalStaffMemberIds(HOSPITAL_ID)).willReturn(List.of(21L, 22L));

        pusher.push(NotificationRecipientType.HOSPITAL, HOSPITAL_ID, payload);

        verify(registry).send(21L, EVENT_NAME, payload);
        verify(registry).send(22L, EVENT_NAME, payload);
        // hospitalId 자체를 연결 키로 쓰지 않는다(레지스트리 키는 계속 memberId다).
        verify(registry, never()).send(eq(HOSPITAL_ID), any(), any());
        verifyNoMoreInteractions(registry);
    }

    @Test
    @DisplayName("한 스태프 전송이 런타임 예외로 실패해도 나머지 스태프에게는 계속 전송한다")
    void hospitalRecipient_isolatesFailurePerStaff() {
        given(memberService.findActiveHospitalStaffMemberIds(HOSPITAL_ID)).willReturn(List.of(21L, 22L));
        // 레지스트리가 흡수하지 않는 런타임 예외(직렬화·연결 상태 등)를 첫 스태프에서 터뜨린다.
        willThrow(new IllegalStateException("첫 스태프 전송 채널 장애")).given(registry).send(21L, EVENT_NAME, payload);

        assertThatCode(() -> pusher.push(NotificationRecipientType.HOSPITAL, HOSPITAL_ID, payload))
                .doesNotThrowAnyException();

        // 앞선 실패가 뒤 스태프의 전달을 삼키지 않는다(리뷰 지적 P2).
        verify(registry).send(22L, EVENT_NAME, payload);
    }

    @Test
    @DisplayName("소속 스태프가 없는 병원 알림은 아무 연결로도 전송하지 않고 예외 없이 끝난다")
    void hospitalRecipient_withoutStaff_isSilentNoOp() {
        given(memberService.findActiveHospitalStaffMemberIds(HOSPITAL_ID)).willReturn(List.of());

        assertThatCode(() -> pusher.push(NotificationRecipientType.HOSPITAL, HOSPITAL_ID, payload))
                .doesNotThrowAnyException();

        verify(registry, never()).send(anyLong(), any(), any());
    }
}
