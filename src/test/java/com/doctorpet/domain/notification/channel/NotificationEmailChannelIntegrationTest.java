package com.doctorpet.domain.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.NotificationPreference;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.repository.NotificationPreferenceRepository;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.global.gateway.mail.EmailGateway;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Level 3 — 이메일 채널이 실제 리스너 배선·실제 MySQL에서 커밋 이후에만, 대상 알림에만 1통 나가는지 검증한다
 * (고도화 3.9).
 *
 * <p>여기서 확인하는 계약은 세 가지다. ① 저장 트랜잭션이 <b>커밋된 뒤에만</b> 발송한다(롤백이면 저장도 메일도
 * 없다 — 트랜잭션 안에서 외부 호출을 하지 않는다는 불변식, SA §9-8). ② 저장된 알림 1행당 1통이다(중복 방지를
 * 채널이 아니라 저장의 멱등에 위임한 근거). ③ 회원이 끈 유형은 나가지 않는다(수신 설정 스키마가 실제로
 * 작동하는지 — 설정 API는 이 범위가 아니라 행을 직접 넣어 확인한다).
 *
 * <p>테스트 메서드를 {@code @Transactional}로 두면 JUnit이 끝에서 롤백해 AFTER_COMMIT 리스너가 영원히 실행되지
 * 않으므로, {@link TransactionTemplate}으로 실제 커밋/롤백되는 트랜잭션 안에서 저장을 호출한다
 * ({@code NotificationCommitPushIntegrationTest}와 같은 관례).
 *
 * <p>전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class NotificationEmailChannelIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 실제 배선(리스너 → 채널 → 게이트웨이)을 그대로 태우고 발송 호출만 관찰한다. mail.provider=fake라
    // 유일한 빈은 FakeEmailGateway다.
    @MockitoSpyBean
    private EmailGateway emailGateway;

    private TransactionTemplate transactionTemplate;
    private Long verifiedMemberId;
    private String verifiedEmail;
    private Long unverifiedMemberId;

    private final List<Long> notificationIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> preferenceIds = new ArrayList<>();

    @BeforeEach
    void setUpMembers() {
        long base = Math.abs(System.nanoTime());
        verifiedEmail = "email-channel-verified-" + base + "@example.com";
        verifiedMemberId = saveMember(verifiedEmail, true);
        unverifiedMemberId = saveMember("email-channel-unverified-" + base + "@example.com", false);
    }

    @AfterEach
    void cleanUp() {
        preferenceIds.forEach(id ->
                jdbcTemplate.update("delete from notification_preferences where id = ?", id));
        notificationIds.forEach(id -> jdbcTemplate.update("delete from notifications where id = ?", id));
        memberIds.forEach(id -> jdbcTemplate.update("delete from members where id = ?", id));
    }

    @Test
    @DisplayName("예약 승인 알림은 커밋 이후 인증된 회원 이메일로 1통 발송된다")
    void reservationConfirmed_sendsOneEmailAfterCommit() {
        Long id = createInNewTransaction(
                verifiedMemberId, NotificationType.RESERVATION_CONFIRMED, "예약이 승인되었습니다.");

        assertThat(id).isNotNull();
        verify(emailGateway, times(1))
                .send(eq(verifiedEmail), contains("예약이 승인"), contains("예약이 승인되었습니다."));
    }

    @Test
    @DisplayName("저장 트랜잭션이 롤백되면 이메일도 나가지 않는다")
    void rollback_sendsNoEmail() {
        assertThatRollbackLeavesNoNotification();

        verify(emailGateway, never()).send(eq(verifiedEmail), any(), any());
    }

    @Test
    @DisplayName("이메일 대상이 아닌 유형은 저장만 되고 발송되지 않는다")
    void nonEmailType_storesWithoutSending() {
        Long id = createInNewTransaction(
                verifiedMemberId, NotificationType.NO_SHOW, "노쇼로 처리되었습니다.");

        assertThat(rowExists(id)).isTrue();
        verify(emailGateway, never()).send(eq(verifiedEmail), any(), any());
    }

    @Test
    @DisplayName("이메일 미인증 회원에게는 저장만 되고 발송되지 않는다")
    void unverifiedMember_storesWithoutSending() {
        Long id = createInNewTransaction(
                unverifiedMemberId, NotificationType.PAYMENT_RESULT, "진료비 결제가 완료되었습니다.");

        assertThat(rowExists(id)).isTrue();
        verify(emailGateway, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("회원이 그 유형의 이메일 수신을 끄면 저장만 되고 발송되지 않는다")
    void disabledPreference_storesWithoutSending() {
        savePreference(verifiedMemberId, NotificationType.PAYMENT_RESULT, false);

        Long id = createInNewTransaction(
                verifiedMemberId, NotificationType.PAYMENT_RESULT, "진료비 결제가 완료되었습니다.");

        assertThat(rowExists(id)).isTrue();
        verify(emailGateway, never()).send(eq(verifiedEmail), any(), any());
    }

    @Test
    @DisplayName("수신 설정을 켜 둔 행이 있으면 그대로 발송된다")
    void enabledPreference_sendsEmail() {
        savePreference(verifiedMemberId, NotificationType.PAYMENT_RESULT, true);

        createInNewTransaction(verifiedMemberId, NotificationType.PAYMENT_RESULT, "진료비 결제가 완료되었습니다.");

        verify(emailGateway, times(1)).send(eq(verifiedEmail), contains("결제 결과"), any());
    }

    private void assertThatRollbackLeavesNoNotification() {
        try {
            transactionTemplate().execute(status -> {
                notificationService.create(
                        verifiedMemberId,
                        NotificationType.RESERVATION_CONFIRMED,
                        "예약이 승인되었습니다.",
                        NotificationResourceType.RESERVATION,
                        1L);
                status.setRollbackOnly();
                return null;
            });
        } catch (RuntimeException ignored) {
            // 롤백 자체가 목적이므로 예외는 무시한다.
        }
    }

    private Long createInNewTransaction(Long memberId, NotificationType type, String content) {
        Notification saved = transactionTemplate().execute(status -> notificationService.create(
                memberId, type, content, NotificationResourceType.RESERVATION, 1L));
        Long id = saved == null ? null : saved.getId();
        if (id != null) {
            notificationIds.add(id);
        }
        return id;
    }

    private TransactionTemplate transactionTemplate() {
        if (transactionTemplate == null) {
            transactionTemplate = new TransactionTemplate(transactionManager);
        }
        return transactionTemplate;
    }

    private Long saveMember(String email, boolean verified) {
        Member member = Member.createGuardian(email, "encoded", "보호자");
        if (verified) {
            member.verifyEmail();
        }
        Long id = memberRepository.saveAndFlush(member).getId();
        memberIds.add(id);
        return id;
    }

    private void savePreference(Long memberId, NotificationType type, boolean enabled) {
        Long id = preferenceRepository.saveAndFlush(
                NotificationPreference.of(memberId, type, NotificationChannelType.EMAIL, enabled)).getId();
        preferenceIds.add(id);
    }

    private boolean rowExists(Long notificationId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from notifications where id = ?", Integer.class, notificationId);
        return count != null && count == 1;
    }
}
