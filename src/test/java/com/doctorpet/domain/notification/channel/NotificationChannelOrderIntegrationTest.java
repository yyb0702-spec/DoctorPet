package com.doctorpet.domain.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.entity.status.NotificationChannelType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Level 3 — 전달 채널 호출 순서가 실시간 → 이메일로 고정되는지 실제 컨텍스트로 검증한다(리뷰 지적 P1).
 *
 * <p>리스너는 주입된 순서대로 채널을 <b>동기</b> 호출한다. 외부 SMTP 왕복이 먼저 오면 그게 끝날 때까지 SSE 전달과
 * 요청 응답이 함께 막히므로, 순서가 뒤집히면 예약 승인·결제 확정 응답 시간이 메일 서버 상태에 묶인다.
 *
 * <p>{@code @Order} 없이는 빈 등록 순서(클래스명 알파벳)에 따라 이메일이 먼저 주입됐다 — 실측으로 확인한 값이고,
 * 클래스명만 바뀌어도 뒤집히는 우연이었다. 그 우연에 기대지 않도록 순서를 이 테스트로 못박는다.
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
class NotificationChannelOrderIntegrationTest {

    @Autowired
    private List<NotificationChannel> channels;

    @Test
    @DisplayName("실시간 채널이 외부 채널(이메일)보다 먼저 호출된다")
    void realtimeChannelComesBeforeEmail() {
        List<NotificationChannelType> order = channels.stream().map(NotificationChannel::type).toList();

        assertThat(order)
                .as("외부 왕복이 있는 채널이 먼저 오면 SSE 전달과 요청 응답이 그 왕복만큼 막힌다")
                .containsSubsequence(NotificationChannelType.REALTIME, NotificationChannelType.EMAIL);
    }
}
