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
}
