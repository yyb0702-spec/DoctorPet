package com.doctorpet.domain.notification.push;

// NotificationPusher의 SSE 구현. 회원(MEMBER) 수신자는 memberId 키 레지스트리로 "notification" 이벤트를 전송하고,
// 병원(HOSPITAL) 수신자는 그 병원에 현재 소속된 스태프의 연결들로 같은 payload를 fan-out한다(SA §9-8, 고도화 3.10).

import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SseNotificationPusher implements NotificationPusher {

    private static final String EVENT_NAME = "notification";

    private final SseEmitterRegistry registry;
    // 병원 → 소속 스태프(memberId) 해석. 알림 도메인이 소속 정보를 따로 들고 있지 않도록 member 도메인 Service를
    // 경유한다(구현 가드레일, NotificationRecipientResolver와 같은 근거).
    private final MemberService memberService;

    @Override
    public void push(
            NotificationRecipientType recipientType,
            Long recipientId,
            NotificationResponse notification
    ) {
        // 수신자 유형별 경로를 명시적으로 나눈다 — 새 유형이 생겨도 회원 경로로 흘러들어 오배달되지 않게,
        // 알지 못하는 유형은 전송하지 않는다(저장은 이미 끝났고 폴링으로 도달한다).
        if (recipientType == NotificationRecipientType.MEMBER) {
            registry.send(recipientId, EVENT_NAME, notification);
            return;
        }
        if (recipientType == NotificationRecipientType.HOSPITAL) {
            pushToHospitalStaff(recipientId, notification);
        }
    }

    /*
      병원 수신 알림의 fan-out. 저장은 병원 단위 1건이고 읽음도 병원 단위로 공유되며, 여기서 하는 일은 이미 저장된
      같은 payload를 접속 중인 스태프들에게 나눠 보내는 전달뿐이다 — 스태프별 알림 행이나 개별 읽음 상태는 만들지 않는다.

      대상은 "전송 시점의 현재 소속"으로만 해석한다. 탈퇴·소속 해제된 스태프의 SSE 연결이 아직 살아 있어도
      조회 결과에서 빠지므로 병원 알림이 가지 않는다(요청이 보낸 hospitalId나 캐시된 옛 소속을 쓰지 않는 이유).

      알려진 한계: SseEmitterRegistry가 인메모리라 fan-out은 이 인스턴스에 연결된 스태프까지만 도달한다(단일 인스턴스
      기준, SA §9-8). 다중 인스턴스 확장 시 Redis pub/sub 등으로 함께 해소한다 — 그때까지도 저장이 알림의 원본이고
      전송은 부가 채널이라, 도달하지 못한 스태프는 목록 조회(폴링)로 같은 알림을 받는다.
     */
    private void pushToHospitalStaff(Long hospitalId, NotificationResponse notification) {
        List<Long> staffMemberIds = memberService.findActiveHospitalStaffMemberIds(hospitalId);
        // 소속 스태프가 없거나 아무도 접속해 있지 않으면 보낼 곳이 없다 — registry.send가 연결 없는 회원을 무시하므로
        // 별도 분기 없이 조용히 끝난다.
        for (Long staffMemberId : staffMemberIds) {
            registry.send(staffMemberId, EVENT_NAME, notification);
        }
    }
}
