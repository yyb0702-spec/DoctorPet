package com.doctorpet.domain.chat.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 기존 MySQL chat_messages의 멱등키 백필·제약 적용·재실행 안전성을 검증한다. */
@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "chat.client-message-id-migration.enabled=false"
})
class ChatMessageClientMessageIdMigrationIntegrationTest {

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ChatMessageClientMessageIdMigrationRunner runner;
    private final List<Long> legacyMessageIds = new java.util.ArrayList<>();

    @BeforeEach
    void prepareLegacySchema() {
        runner = new ChatMessageClientMessageIdMigrationRunner(jdbcTemplate);
        jdbcTemplate.update("delete from schema_migrations where migration_key = ?",
                ChatMessageClientMessageIdMigrationRunner.MIGRATION_KEY);
        jdbcTemplate.execute("drop trigger if exists "
                + ChatMessageClientMessageIdMigrationRunner.BACKFILL_TRIGGER);
        dropUniqueIndex();
        jdbcTemplate.execute("alter table chat_messages "
                + "modify column client_message_id varchar(36) null");
    }

    @AfterEach
    void restoreMigratedSchema() {
        legacyMessageIds.forEach(id -> jdbcTemplate.update("delete from chat_messages where id = ?", id));
        jdbcTemplate.update("update chat_messages set client_message_id = uuid() "
                + "where client_message_id is null or char_length(trim(client_message_id)) = 0");
        dropUniqueIndex();
        jdbcTemplate.execute("create unique index "
                + ChatMessageClientMessageIdMigrationRunner.UNIQUE_INDEX_NAME
                + " on chat_messages (reservation_id, member_id, client_message_id)");
        jdbcTemplate.execute("alter table chat_messages "
                + "modify column client_message_id varchar(36) not null");
        jdbcTemplate.execute("drop trigger if exists "
                + ChatMessageClientMessageIdMigrationRunner.BACKFILL_TRIGGER);
        jdbcTemplate.update("delete from schema_migrations where migration_key = ?",
                ChatMessageClientMessageIdMigrationRunner.MIGRATION_KEY);
        runner.run(null);
    }

    @Test
    @DisplayName("동일 발신자의 기존 메시지 여러 건을 UUID로 백필한 뒤 UNIQUE·NOT NULL을 적용한다")
    void legacyRows_areBackfilledBeforeUniqueAndNotNull() {
        long reservationId = Math.abs(System.nanoTime());
        long memberId = reservationId + 1;
        insertLegacyMessage(reservationId, memberId, "기존 메시지 1");
        insertLegacyMessage(reservationId, memberId, "기존 메시지 2");

        runner.run(null);

        List<String> clientMessageIds = jdbcTemplate.queryForList("""
                select client_message_id from chat_messages
                 where id in (?, ?) order by id
                """, String.class, legacyMessageIds.get(0), legacyMessageIds.get(1));
        assertThat(clientMessageIds).hasSize(2).allSatisfy(id -> assertThat(UUID.matcher(id).matches()).isTrue());
        assertThat(clientMessageIds).doesNotHaveDuplicates();
        assertThat(columnNullable()).isFalse();
        assertThat(uniqueIndexExists()).isTrue();
        assertThat(triggerExists()).isTrue();
        assertThat(markerExists()).isTrue();
    }

    @Test
    @DisplayName("완료 후 구버전 INSERT는 트리거로 UUID를 받고 재실행해도 기존 UUID를 바꾸지 않는다")
    void legacyInsertAfterMigration_isCompatibleAndRerunIsIdempotent() {
        runner.run(null);
        long reservationId = Math.abs(System.nanoTime());
        long memberId = reservationId + 1;
        jdbcTemplate.update("""
                insert into chat_messages
                       (reservation_id, sender_type, hospital_id, member_id, body, created_at)
                values (?, 'GUARDIAN', ?, ?, '구버전 메시지', now(6))
                """, reservationId, reservationId + 2, memberId);
        Long insertedId = jdbcTemplate.queryForObject("""
                select id from chat_messages
                 where reservation_id = ? and member_id = ? order by id desc limit 1
                """, Long.class, reservationId, memberId);
        legacyMessageIds.add(insertedId);
        String beforeRerun = clientMessageId(insertedId);

        runner.run(null);

        assertThat(UUID.matcher(beforeRerun).matches()).isTrue();
        assertThat(clientMessageId(insertedId)).isEqualTo(beforeRerun);
        assertThat(columnNullable()).isFalse();
        assertThat(uniqueIndexExists()).isTrue();
        assertThat(markerExists()).isTrue();
    }

    @Test
    @DisplayName("두 인스턴스가 동시에 기동해도 DB 잠금으로 백필·제약 적용을 한 번만 직렬화한다")
    void concurrentRunners_areSerializedByDatabaseLock() throws InterruptedException {
        long reservationId = Math.abs(System.nanoTime());
        insertLegacyMessage(reservationId, reservationId + 1, "동시 기동 이전 메시지");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    runner.run(null);
                } catch (Throwable throwable) {
                    errors.add(throwable);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(UUID.matcher(clientMessageId(legacyMessageIds.get(0))).matches()).isTrue();
        assertThat(columnNullable()).isFalse();
        assertThat(uniqueIndexExists()).isTrue();
        assertThat(markerExists()).isTrue();
    }

    private void insertLegacyMessage(long reservationId, long memberId, String body) {
        jdbcTemplate.update("""
                insert into chat_messages
                       (reservation_id, sender_type, hospital_id, member_id, body, client_message_id, created_at)
                values (?, 'GUARDIAN', ?, ?, ?, null, now(6))
                """, reservationId, reservationId + 2, memberId, body);
        Long id = jdbcTemplate.queryForObject("""
                select id from chat_messages
                 where reservation_id = ? and member_id = ? and body = ? order by id desc limit 1
                """, Long.class, reservationId, memberId, body);
        legacyMessageIds.add(id);
    }

    private String clientMessageId(Long id) {
        return jdbcTemplate.queryForObject(
                "select client_message_id from chat_messages where id = ?", String.class, id);
    }

    private boolean columnNullable() {
        String nullable = jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_schema = database() and table_name = 'chat_messages'
                   and column_name = 'client_message_id'
                """, String.class);
        return "YES".equalsIgnoreCase(nullable);
    }

    private boolean uniqueIndexExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from (
                    select index_name from information_schema.statistics
                     where table_schema = database() and table_name = 'chat_messages'
                       and index_name = ? and non_unique = 0
                     group by index_name
                    having group_concat(column_name order by seq_in_index)
                           = 'reservation_id,member_id,client_message_id'
                ) matching_indexes
                """, Integer.class, ChatMessageClientMessageIdMigrationRunner.UNIQUE_INDEX_NAME);
        return count != null && count > 0;
    }

    private boolean triggerExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.triggers
                 where trigger_schema = database() and trigger_name = ?
                """, Integer.class, ChatMessageClientMessageIdMigrationRunner.BACKFILL_TRIGGER);
        return count != null && count > 0;
    }

    private boolean markerExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from schema_migrations where migration_key = ?
                """, Integer.class, ChatMessageClientMessageIdMigrationRunner.MIGRATION_KEY);
        return count != null && count > 0;
    }

    private void dropUniqueIndex() {
        if (uniqueIndexNameExists()) {
            jdbcTemplate.execute("drop index "
                    + ChatMessageClientMessageIdMigrationRunner.UNIQUE_INDEX_NAME + " on chat_messages");
        }
    }

    private boolean uniqueIndexNameExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.statistics
                 where table_schema = database() and table_name = 'chat_messages' and index_name = ?
                """, Integer.class, ChatMessageClientMessageIdMigrationRunner.UNIQUE_INDEX_NAME);
        return count != null && count > 0;
    }
}
