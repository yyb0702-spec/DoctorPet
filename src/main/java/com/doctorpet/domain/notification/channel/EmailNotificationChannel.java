package com.doctorpet.domain.notification.channel;

// 이메일 전달 채널(고도화 3.9). SSE는 앱이 연결돼 있을 때만 도달하므로 앱을 닫은 사용자를 이메일이 메운다.

import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.NotificationPreference;
import com.doctorpet.domain.notification.entity.status.NotificationChannelType;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.repository.NotificationPreferenceRepository;
import com.doctorpet.global.gateway.mail.EmailGateway;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 저장된 알림을 이메일로도 보내는 채널.
 *
 * <p><b>대상은 예약 승인·결제 결과 두 유형뿐이다</b>(고도화 3.9 결정) — 사용자가 놓치면 곤란한 결과성 알림만
 * 이메일로 밀고, 나머지는 인앱·SSE로 둔다. 유형을 늘리는 것은 정책 변경이므로 이 표를 함부로 넓히지 않는다.
 * "결제 확인 중"(PAYMENT_PENDING)은 결과가 아니라 중간 안내라 제외한다.
 *
 * <p><b>회원 수신(MEMBER)만 보낸다.</b> 병원 수신(HOSPITAL)은 병원 단위 공유 알림이고 병원 대표 메일 주소·스태프
 * 개인 메일 중 무엇으로 보낼지가 정해지지 않았다(고도화 3.9 범위 밖) — 조용히 no-op한다.
 *
 * <p><b>인증된 이메일만 보낸다.</b> 미인증 주소로 보내면 그 주소의 실제 소유자(회원이 아닌 제3자)에게 진료·결제
 * 정보가 새어 나갈 수 있다. 탈퇴 회원은 이메일이 익명화되고 Member의 soft delete 조건에서 빠지므로 대상이 아니다.
 *
 * <p><b>중복 발송:</b> 이 채널은 저장된 알림 1행당 1회만 호출된다({@code NotificationPushListener}가 저장 커밋
 * 이벤트를 받아 호출). 그래서 이메일의 멱등은 저장의 멱등을 그대로 승계한다 — 멱등 발행(dedup_key UNIQUE
 * {@code uk_notifications_dedup_key})은 행이 1건이므로 메일도 1통이고, 일반 발행은 행이 2건이면 서로 다른 사건이라
 * (예: 결제 완료 뒤 환불된 PAYMENT_RESULT) 2통이 맞다. 이 채널에 별도의 중복 방지 장치를 두지 않는 이유다.
 * 반대로 커밋 직후 프로세스가 죽으면 그 1통은 재시도 없이 유실된다 — 인앱 저장은 남으므로 사용자는 목록에서
 * 확인할 수 있고, 전달 보장은 아웃박스(고도화 3.11)의 범위다.
 *
 * <p>발송 예외는 여기서 잡지 않는다 — 리스너가 채널별로 격리해 삼키므로 이메일 실패가 SSE 전달이나 저장에
 * 영향을 주지 않는다(이중 처리를 피한다).
 */
@Slf4j
@Component
// 실시간 채널보다 뒤에 둔다(리뷰 지적 P1). 리스너는 채널을 순서대로 동기 호출하므로, 외부 SMTP를 먼저 부르면
// 그 왕복이 끝날 때까지 SSE 전달과 요청 응답이 함께 막힌다. 순서를 명시하지 않으면 빈 등록 순서(클래스명
// 알파벳)에 따라 이메일이 먼저 도는데, 그건 결정이 아니라 우연이다.
@Order(NotificationChannel.EMAIL_ORDER)
@ConditionalOnProperty(name = "notification.email.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class EmailNotificationChannel implements NotificationChannel {

    // 이메일로 보내는 유형과 제목. 본문은 알림 content 스냅샷을 그대로 쓴다 — 알림 문구는 이미 동적 PII를 담지
    // 않도록 발행부에서 관리되므로(SA §4 notifications.content), 채널이 문구를 새로 조립해 그 원칙을 깨지 않는다.
    private static final Map<NotificationType, String> SUBJECTS = Map.of(
            NotificationType.RESERVATION_CONFIRMED, "[DoctorPet] 예약이 승인되었습니다",
            NotificationType.PAYMENT_RESULT, "[DoctorPet] 진료비 결제 결과를 알려드립니다"
    );
    private static final String BODY_FOOTER = "\n\n자세한 내용은 DoctorPet에서 확인하실 수 있습니다.";

    private final EmailGateway emailGateway;
    private final MemberService memberService;
    private final NotificationPreferenceRepository preferenceRepository;

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.EMAIL;
    }

    @Override
    public void deliver(
            NotificationRecipientType recipientType,
            Long recipientId,
            NotificationType type,
            NotificationResponse notification
    ) {
        if (recipientType != NotificationRecipientType.MEMBER) {
            return;
        }
        String subject = SUBJECTS.get(type);
        if (subject == null) {
            return;
        }
        if (!isEnabled(recipientId, type)) {
            return;
        }

        Optional<String> email = memberService.findActiveVerifiedEmail(recipientId);
        if (email.isEmpty()) {
            // 미인증·탈퇴·부재 회원. 인앱 저장은 이미 남았으므로 조용히 건너뛴다(경고 아님 — 정상 경로다).
            log.debug("알림 이메일 발송 생략(인증된 이메일 없음): memberId={} notificationId={}",
                    recipientId, notification.id());
            return;
        }

        emailGateway.send(email.get(), subject, notification.content() + BODY_FOOTER);
    }

    // 회원이 그 유형의 이메일 수신을 끈 적이 있는지 본다. 설정 행이 없으면 수신(기본 on)이라, 설정을 만들지 않은
    // 기존 회원의 동작이 바뀌지 않는다. 설정을 바꾸는 API·화면은 이 PR 범위가 아니다(고도화 3.9).
    private boolean isEnabled(Long memberId, NotificationType type) {
        return preferenceRepository
                .findByMemberIdAndNotificationTypeAndChannel(memberId, type, NotificationChannelType.EMAIL)
                .map(NotificationPreference::isEnabled)
                .orElse(true);
    }
}
