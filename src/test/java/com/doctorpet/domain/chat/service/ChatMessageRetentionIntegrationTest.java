package com.doctorpet.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.chat.entity.ChatMessage;
import com.doctorpet.domain.chat.entity.ChatSenderType;
import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 만료 보존 정리는 엔티티 로딩 없이 실제 MySQL bulk DELETE로 처리한다. */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "jwt.secret=doctorpet-chat-integration-test-secret-key-32-bytes-minimum",
        "jwt.access-token-expiration=3600000",
        "jwt.refresh-token-expiration=1209600000"
})
class ChatMessageRetentionIntegrationTest {

    @Autowired private ChatMessageService chatMessageService;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long expiredMessageId;
    private Long retainedMessageId;

    @AfterEach
    void cleanUp() {
        if (expiredMessageId != null) {
            chatMessageRepository.deleteById(expiredMessageId);
        }
        if (retainedMessageId != null) {
            chatMessageRepository.deleteById(retainedMessageId);
        }
    }

    @Test
    @DisplayName("1년 초과 메시지만 bulk DELETE하고 최신 메시지는 보존한다")
    void deletesOnlyExpiredMessagesWithBulkDelete() {
        expiredMessageId = chatMessageRepository.saveAndFlush(message("만료 메시지")).getId();
        retainedMessageId = chatMessageRepository.saveAndFlush(message("보존 메시지")).getId();
        jdbcTemplate.update("update chat_messages set created_at = ? where id = ?",
                LocalDateTime.now().minusYears(1).minusDays(1), expiredMessageId);

        long deleted = chatMessageService.deleteExpiredMessages();

        assertThat(deleted).isEqualTo(1);
        assertThat(chatMessageRepository.findById(expiredMessageId)).isEmpty();
        assertThat(chatMessageRepository.findById(retainedMessageId)).isPresent();
    }

    private ChatMessage message(String body) {
        return ChatMessage.create(System.nanoTime(), ChatSenderType.GUARDIAN,
                System.nanoTime(), System.nanoTime(), body);
    }
}
