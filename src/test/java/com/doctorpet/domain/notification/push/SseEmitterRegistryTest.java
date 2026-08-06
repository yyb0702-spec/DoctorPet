package com.doctorpet.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SseEmitterRegistryTest {

    private static final Long MEMBER_A = 1L;
    private static final Long MEMBER_B = 2L;

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    @Test
    @DisplayName("등록하면 해당 수신자의 연결 수가 늘고, 수신자별로 격리된다")
    void register_countsPerMember() {
        registry.register(MEMBER_A);
        registry.register(MEMBER_A);
        registry.register(MEMBER_B);

        assertThat(registry.connectionCount(MEMBER_A)).isEqualTo(2);
        assertThat(registry.connectionCount(MEMBER_B)).isEqualTo(1);
        assertThat(registry.connectionCount(999L)).isZero();
    }

    @Test
    @DisplayName("연결이 없는 수신자에게 전송해도 예외 없이 무시된다(저장이 원본, 전송은 부가 채널)")
    void send_toMemberWithoutConnection_isNoOp() {
        assertThatCode(() -> registry.send(MEMBER_A, "notification", "payload"))
                .doesNotThrowAnyException();
    }
}
