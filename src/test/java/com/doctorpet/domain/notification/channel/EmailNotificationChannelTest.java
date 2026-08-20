package com.doctorpet.domain.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.NotificationPreference;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.repository.NotificationPreferenceRepository;
import com.doctorpet.global.gateway.mail.EmailGateway;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Level 1 — 이메일 채널의 발송 대상 판정을 검증한다(고도화 3.9).
 *
 * <p>이 채널의 계약은 "무엇을 보내는가"보다 <b>무엇을 보내지 않는가</b>가 핵심이다 — 유형을 넓히는 것은 정책
 * 변경이고, 미인증 주소·병원 수신으로 새는 것은 개인정보 문제다. 그래서 제외 조건을 유형별로 못박는다.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationChannelTest {

    private static final Long MEMBER_ID = 4001L;
    private static final String EMAIL = "guardian@example.com";

    @Mock
    private EmailGateway emailGateway;

    @Mock
    private MemberService memberService;

    @Mock
    private NotificationPreferenceRepository preferenceRepository;

    @InjectMocks
    private EmailNotificationChannel channel;

    private NotificationResponse notification;

    @BeforeEach
    void setUp() {
        notification = new NotificationResponse(
                77L,
                NotificationType.RESERVATION_CONFIRMED.name(),
                "예약이 승인되었습니다.",
                NotificationResourceType.RESERVATION.name(),
                55L,
                false,
                null,
                LocalDateTime.of(2026, 8, 21, 10, 0)
        );
    }

    @Test
    @DisplayName("채널 종류는 EMAIL이다")
    void type_isEmail() {
        assertThat(channel.type()).isEqualTo(NotificationChannelType.EMAIL);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {"RESERVATION_CONFIRMED", "PAYMENT_RESULT"})
    @DisplayName("예약 승인·결제 결과는 인증된 회원 이메일로 알림 문구를 그대로 보낸다")
    void deliver_sendsForApprovalAndPaymentResult(NotificationType type) {
        when(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                MEMBER_ID, type, NotificationChannelType.EMAIL)).thenReturn(Optional.empty());
        when(memberService.findActiveVerifiedEmail(MEMBER_ID)).thenReturn(Optional.of(EMAIL));

        channel.deliver(NotificationRecipientType.MEMBER, MEMBER_ID, type, notification);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailGateway).send(eq(EMAIL), subject.capture(), body.capture());
        assertThat(subject.getValue()).startsWith("[DoctorPet]");
        // 본문은 저장된 알림 문구 스냅샷을 그대로 쓴다 — 채널이 문구를 새로 조립하면 발행부가 관리하는
        // "동적 PII 미포함" 원칙이 채널에서 깨질 수 있다.
        assertThat(body.getValue()).startsWith(notification.content());
    }

    @ParameterizedTest
    @EnumSource(
            value = NotificationType.class,
            names = {"RESERVATION_CONFIRMED", "PAYMENT_RESULT"},
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("예약 승인·결제 결과가 아닌 유형은 이메일을 보내지 않는다")
    void deliver_skipsOtherTypes(NotificationType type) {
        channel.deliver(NotificationRecipientType.MEMBER, MEMBER_ID, type, notification);

        verifyNoInteractions(emailGateway);
        // 대상 유형이 아니면 회원 조회·설정 조회도 하지 않는다(불필요한 DB 왕복 방지).
        verifyNoInteractions(memberService, preferenceRepository);
    }

    @Test
    @DisplayName("병원 수신 알림은 이메일 대상 유형이어도 보내지 않는다")
    void deliver_skipsHospitalRecipient() {
        // 유형은 이메일 대상(RESERVATION_CONFIRMED)으로 둔다 — 병원 전용 유형(RESERVATION_REQUESTED)으로 쓰면
        // 유형 가드에 먼저 걸려 통과하고, recipientType 가드를 지워도 테스트가 깨지지 않는다(뮤테이션으로 확인).
        channel.deliver(
                NotificationRecipientType.HOSPITAL, 9L, NotificationType.RESERVATION_CONFIRMED, notification);

        verifyNoInteractions(emailGateway, memberService, preferenceRepository);
    }

    @Test
    @DisplayName("인증된 이메일이 없으면(미인증·탈퇴·부재) 보내지 않는다")
    void deliver_skipsWhenNoVerifiedEmail() {
        when(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                anyLong(), any(), any())).thenReturn(Optional.empty());
        when(memberService.findActiveVerifiedEmail(MEMBER_ID)).thenReturn(Optional.empty());

        channel.deliver(
                NotificationRecipientType.MEMBER, MEMBER_ID, NotificationType.RESERVATION_CONFIRMED, notification);

        verify(emailGateway, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("회원이 그 유형의 이메일 수신을 끄면 보내지 않는다")
    void deliver_skipsWhenPreferenceDisabled() {
        when(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                MEMBER_ID, NotificationType.RESERVATION_CONFIRMED, NotificationChannelType.EMAIL))
                .thenReturn(Optional.of(NotificationPreference.of(
                        MEMBER_ID,
                        NotificationType.RESERVATION_CONFIRMED,
                        NotificationChannelType.EMAIL,
                        false)));

        channel.deliver(
                NotificationRecipientType.MEMBER, MEMBER_ID, NotificationType.RESERVATION_CONFIRMED, notification);

        verify(emailGateway, never()).send(any(), any(), any());
        // 끈 설정이면 주소 조회 자체를 하지 않는다.
        verifyNoInteractions(memberService);
    }

    @Test
    @DisplayName("설정 행이 없으면 수신이 기본이다")
    void deliver_defaultsToEnabledWhenNoPreferenceRow() {
        when(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                MEMBER_ID, NotificationType.PAYMENT_RESULT, NotificationChannelType.EMAIL))
                .thenReturn(Optional.empty());
        when(memberService.findActiveVerifiedEmail(MEMBER_ID)).thenReturn(Optional.of(EMAIL));

        channel.deliver(
                NotificationRecipientType.MEMBER, MEMBER_ID, NotificationType.PAYMENT_RESULT, notification);

        verify(emailGateway).send(eq(EMAIL), any(), any());
    }

    @Test
    @DisplayName("발송 예외는 채널이 삼키지 않고 그대로 던진다(리스너가 채널별로 격리한다)")
    void deliver_propagatesGatewayFailure() {
        when(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                anyLong(), any(), any())).thenReturn(Optional.empty());
        when(memberService.findActiveVerifiedEmail(MEMBER_ID)).thenReturn(Optional.of(EMAIL));
        doThrow(new IllegalStateException("SMTP 장애")).when(emailGateway).send(any(), any(), any());

        // 채널 안에서 또 삼키면 이중 처리가 되고, 리스너의 채널별 격리 로그가 남지 않는다.
        assertThatThrownBy(() -> channel.deliver(
                NotificationRecipientType.MEMBER, MEMBER_ID, NotificationType.RESERVATION_CONFIRMED, notification))
                .isInstanceOf(IllegalStateException.class);
    }
}
