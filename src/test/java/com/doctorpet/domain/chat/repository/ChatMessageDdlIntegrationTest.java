package com.doctorpet.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.chat.entity.ChatMessage;
import com.doctorpet.domain.chat.entity.ChatSenderType;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 실제 MySQL DDL에서 채팅 감사 필드·길이·조회 인덱스를 검증한다. */
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
class ChatMessageDdlIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ChatMessageRepository chatMessageRepository;

    @Test
    void chatMessages_usesRequiredColumnsAndCursorIndex() throws Exception {
        Set<String> nonNullable = new HashSet<>();
        Set<String> indexes = new HashSet<>();
        Map<Short, String> idempotencyUniqueColumns = new TreeMap<>();
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, "chat_messages", null)) {
                while (columns.next()) {
                    if (columns.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
                        nonNullable.add(columns.getString("COLUMN_NAME").toLowerCase());
                    }
                    if ("body".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        assertThat(columns.getInt("COLUMN_SIZE")).isGreaterThanOrEqualTo(1000);
                    }
                }
            }
            try (ResultSet indexRows = metaData.getIndexInfo(connection.getCatalog(), null, "chat_messages", false, false)) {
                while (indexRows.next()) {
                    String name = indexRows.getString("INDEX_NAME");
                    if (name != null) {
                        indexes.add(name.toLowerCase());
                        if ("uk_chat_messages_reservation_member_client".equalsIgnoreCase(name)
                                && !indexRows.getBoolean("NON_UNIQUE")) {
                            idempotencyUniqueColumns.put(indexRows.getShort("ORDINAL_POSITION"),
                                    indexRows.getString("COLUMN_NAME").toLowerCase());
                        }
                    }
                }
            }
        }

        assertThat(nonNullable).contains(
                "reservation_id", "sender_type", "hospital_id", "member_id", "body", "client_message_id", "created_at");
        assertThat(indexes).contains(
                "idx_chat_messages_reservation_created", "uk_chat_messages_reservation_member_client");
        assertThat(new ArrayList<>(idempotencyUniqueColumns.values()))
                .containsExactly("reservation_id", "member_id", "client_message_id");
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void markRead_marksOnlyMessagesUpToClientCursor() {
        // 상대(병원) 메시지 3건 중, 클라이언트가 병합한 것은 두 번째까지라고 가정한다.
        long reservationId = System.nanoTime();
        ChatMessage first = chatMessageRepository.saveAndFlush(hospitalMessage(reservationId, "첫 메시지"));
        ChatMessage second = chatMessageRepository.saveAndFlush(hospitalMessage(reservationId, "두 번째 메시지"));
        ChatMessage arrivedLate = chatMessageRepository.saveAndFlush(
                hospitalMessage(reservationId, "복구 뒤 저장돼 아직 화면에 없는 메시지"));

        int updated = chatMessageRepository.markReadByReservationIdAndSenderType(
                reservationId, ChatSenderType.HOSPITAL, second.getId(), java.time.LocalDateTime.now());

        // 커서 이하 2건만 읽음이 되고, 아직 도착하지 않은 메시지는 미읽음으로 남아야 한다.
        assertThat(updated).isEqualTo(2);
        assertThat(chatMessageRepository.findById(first.getId()).orElseThrow().getReadAt()).isNotNull();
        assertThat(chatMessageRepository.findById(second.getId()).orElseThrow().getReadAt()).isNotNull();
        assertThat(chatMessageRepository.findById(arrivedLate.getId()).orElseThrow().getReadAt()).isNull();
    }

    private ChatMessage hospitalMessage(long reservationId, String body) {
        return ChatMessage.create(reservationId, ChatSenderType.HOSPITAL, 1L, 2L, body,
                java.util.UUID.randomUUID().toString());
    }
}
