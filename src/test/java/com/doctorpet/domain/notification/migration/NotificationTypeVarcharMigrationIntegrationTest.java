package com.doctorpet.domain.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — 실제 MySQL에서 notifications.type·resource_type의 ENUM → VARCHAR 전환(이슈 #176)을 검증한다.
 *
 * <p>검증 계약: ① 기존 ENUM 컬럼을 전환하고 재실행해도 멱등하다, ② 기존 행의 값이 보존된다(ENUM 라벨 → 문자열),
 * ③ 마커가 이미 있는데 수동으로 ENUM으로 되돌린 드리프트도 스스로 복구한다, ④ 전환 후에는 ENUM 정의에 없던
 * 새 값도 DDL 없이 저장된다(이 이슈의 목적), ⑤ 제거한 정정 러너가 담당했던 오타 값 보정이 유지된다,
 * ⑥ Hibernate가 VARCHAR enum 컬럼에 만드는 CHECK 제약을 드롭한다.
 *
 * <p>⑥은 <b>신규 DB에서만 재현되는 결함</b>이었다(CI 실패로 발견). 오래된 개발 DB의 notifications에는 CHECK 제약이
 * 없어서(테이블이 ENUM 시절에 만들어졌고 `ddl-auto=update`는 기존 테이블에 CHECK를 추가하지 않는다) 기존 DB로만
 * 통합 테스트를 돌리면 통과한다. 그래서 이 클래스는 CHECK 제약을 <b>직접 만들어 놓고</b> 러너가 드롭하는지 본다 —
 * 신규 DB의 상태를 기존 DB에서 재현하는 것이 목적이다.
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
class NotificationTypeVarcharMigrationIntegrationTest {

    // #176 이전 상태 재현용 ENUM. 현재 NotificationType의 모든 값을 포함해야 한다 — 공유 테스트 DB에 남아 있는
    // 다른 테스트의 행이 ALTER에서 잘려 나가면(MySQL strict 1265) 검증하려는 러너 동작에 도달하지 못한다.
    private static final String LEGACY_TYPE_ENUM =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELED','RESERVATION_REJECTED',"
                    + "'RESERVATION_REQUESTED','RESERVATION_WAITLIST_OFFERED')";
    // 위 정의에 대기열 고도화 전의 오타 값까지 더한 상태(정정 러너를 거치지 않은 DB 사본).
    private static final String LEGACY_TYPE_ENUM_WITH_TYPO =
            "enum('NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELLED','RESERVATION_HOSPITAL_CANCELED',"
                    + "'RESERVATION_REJECTED','RESERVATION_REQUESTED','RESERVATION_WAITLIST_OFFERED')";
    private static final String LEGACY_RESOURCE_TYPE_ENUM =
            "enum('PAYMENT','RESERVATION','RESERVATION_WAITLIST')";
    // 이 테스트가 만든 행만 골라 지우기 위한 표식.
    private static final String TEST_CONTENT = "[#176 전환 테스트] 알림 유형 컬럼 VARCHAR 전환 검증";
    // 현재 enum 값 전체. Hibernate가 신규 DB에 만드는 CHECK 제약과 같은 열거를 재현할 때 쓴다 — 기존 행이
    // 위반하지 않아야 ALTER ADD CONSTRAINT 자체가 성공한다.
    private static final String CURRENT_TYPE_VALUES =
            "'NO_SHOW','PAYMENT_PENDING','PAYMENT_RESULT','RESERVATION_CONFIRMED',"
                    + "'RESERVATION_HOSPITAL_CANCELED','RESERVATION_REJECTED',"
                    + "'RESERVATION_REQUESTED','RESERVATION_WAITLIST_OFFERED'";
    private static final String CURRENT_RESOURCE_TYPE_VALUES =
            "'PAYMENT','RESERVATION','RESERVATION_WAITLIST'";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void restoreSchemaAndData() {
        // 테스트 행을 먼저 지운다 — enum에 없는 값을 남겨두면 다음 테스트의 ALTER가 truncation으로 실패한다.
        jdbcTemplate.update("delete from notifications where content = ?", TEST_CONTENT);
        // 러너는 넓히기만 하므로 varchar(60)으로 넓힌 케이스는 직접 되돌린다(다음 테스트의 시작 상태를 고정).
        jdbcTemplate.execute("alter table notifications modify column type "
                + NotificationTypeVarcharMigrationRunner.TARGET_COLUMN_TYPE + " not null");
        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();
    }

    @Test
    @DisplayName("기존 ENUM 컬럼을 varchar로 전환하고, 두 번 실행해도 멱등하다")
    void migration_convertsEnumColumnsToVarcharAndIsIdempotent() {
        revertToLegacyEnum(LEGACY_TYPE_ENUM);
        deleteMarker();

        NotificationTypeVarcharMigrationRunner runner = new NotificationTypeVarcharMigrationRunner(jdbcTemplate);
        runner.migrateBeforeJpa();
        // 두 번째 실행(부팅 후 ApplicationRunner 경로)이 이미 전환된 스키마를 깨지 않아야 한다.
        runner.run(null);

        assertThat(columnType("type")).isEqualTo(NotificationTypeVarcharMigrationRunner.TARGET_COLUMN_TYPE);
        assertThat(columnType("resource_type"))
                .isEqualTo(NotificationTypeVarcharMigrationRunner.TARGET_COLUMN_TYPE);
        assertThat(columnNullable("type")).isFalse();
        assertThat(columnNullable("resource_type")).isTrue();
        assertThat(markerExists()).isTrue();
    }

    @Test
    @DisplayName("전환이 기존 행의 유형 값을 문자열로 그대로 보존한다")
    void migration_preservesExistingRowValues() {
        revertToLegacyEnum(LEGACY_TYPE_ENUM);
        deleteMarker();
        long id = insertNotification("RESERVATION_CONFIRMED", "RESERVATION");

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(typeOf(id)).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(resourceTypeOf(id)).isEqualTo("RESERVATION");
    }

    @Test
    @DisplayName("마커가 있어도 수동으로 ENUM으로 되돌린 드리프트를 복구한다")
    void migration_recoversFromManualEnumDrift() {
        // 정상 상태(마커 존재)에서 누군가 컬럼만 ENUM으로 되돌린 상황. 마커로 조기 종료하면 전환이 되돌아간 채
        // 남아 값 추가가 다시 깨지므로, 러너는 매 부팅 실제 컬럼 타입을 확인해야 한다.
        revertToLegacyEnum(LEGACY_TYPE_ENUM);
        ensureMarker();

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(columnType("type")).isEqualTo(NotificationTypeVarcharMigrationRunner.TARGET_COLUMN_TYPE);
        assertThat(columnType("resource_type"))
                .isEqualTo(NotificationTypeVarcharMigrationRunner.TARGET_COLUMN_TYPE);
    }

    @Test
    @DisplayName("전환 후에는 ENUM 정의에 없던 새 유형 값도 DDL 없이 저장된다")
    void afterMigration_newTypeValueIsInsertableWithoutDdl() {
        // 이 이슈의 목적 자체를 증명한다 — ENUM이던 동안에는 이 INSERT가 MySQL 1265로 실패했다.
        revertToLegacyEnum(LEGACY_TYPE_ENUM);
        deleteMarker();
        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThatCode(() -> insertNotification("FUTURE_TYPE_NOT_IN_ENUM", "FUTURE_RESOURCE_NOT_IN_ENUM"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 더 넓은 varchar면 줄이지 않고 그대로 둔다")
    void migration_doesNotShrinkWiderVarchar() {
        // 나중에 엔티티 length를 40보다 크게 늘리면 신규 DB 컬럼이 그 길이로 생성된다 — 러너가 정확히 varchar(40)을
        // 요구하면 그걸 되돌려 줄여, 길이를 늘린 이유가 조용히 무너진다. 넓히기만 하는 계약을 고정한다.
        jdbcTemplate.execute("alter table notifications modify column type varchar(60) not null");
        deleteMarker();

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(columnType("type")).isEqualTo("varchar(60)");
        assertThat(markerExists()).isTrue();
    }

    @Test
    @DisplayName("Hibernate가 만든 CHECK 제약을 드롭해, 열거에 없는 값도 저장된다")
    void migration_dropsEnumCheckConstraintsSoNewValuesInsert() {
        // 신규 DB 상태 재현 — Hibernate가 만드는 것과 같은 형태의 CHECK 제약을 직접 만든다. 이게 남아 있으면
        // ENUM을 없앤 의미가 사라진다(값 추가 시 MySQL 1265 대신 3819로 이름만 바뀐 채 같은 증상).
        addCheckConstraint("chk_test_notification_type", "type", CURRENT_TYPE_VALUES);
        addCheckConstraint("chk_test_notification_resource_type", "resource_type", CURRENT_RESOURCE_TYPE_VALUES);
        assertThat(enumCheckConstraintCount()).isEqualTo(2);

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(enumCheckConstraintCount()).isZero();
        assertThatCode(() -> insertNotification("FUTURE_TYPE_NOT_IN_ENUM", "FUTURE_RESOURCE_NOT_IN_ENUM"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recipient_type의 CHECK 제약도 함께 드롭한다")
    void migration_dropsRecipientTypeCheckConstraint() {
        // recipient_type은 타입 전환 대상이 아니지만(이미 varchar(20)) 신규 DB에서는 같은 CHECK가 생긴다.
        addCheckConstraint("chk_test_notification_recipient_type", "recipient_type", "'MEMBER','HOSPITAL'");

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(enumCheckConstraintCount()).isZero();
    }

    @Test
    @DisplayName("오타 값이 남은 DB를 전환하면서 정정한다(제거한 정정 러너의 보정을 승계)")
    void migration_correctsLegacyTypoValue() {
        revertToLegacyEnum(LEGACY_TYPE_ENUM_WITH_TYPO);
        deleteMarker();
        long id = insertNotification("RESERVATION_HOSPITAL_CANCELLED", "RESERVATION");

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(typeOf(id)).isEqualTo("RESERVATION_HOSPITAL_CANCELED");
        assertThat(legacyTypoRowCount()).isZero();
    }

    // Hibernate가 신규 DB에 만드는 것과 같은 형태의 CHECK 제약을 만든다. 이름은 테스트가 정하지만 러너는
    // 이름이 아니라 CHECK 절이 참조하는 컬럼으로 대상을 고르므로, 자동 이름(notifications_chk_N)과 동등하다.
    private void addCheckConstraint(String name, String column, String allowedValues) {
        jdbcTemplate.execute("alter table notifications add constraint `" + name + "` check (`"
                + column + "` in (" + allowedValues + "))");
    }

    // 유형 컬럼(type·resource_type·recipient_type)을 참조하는 CHECK 제약 수. 러너가 고르는 기준과 같은 조건으로
    // 센다 — 러너가 이름을 하드코딩하지 않으므로 테스트도 이름으로 세지 않는다.
    private int enumCheckConstraintCount() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.table_constraints tc
                  join information_schema.check_constraints cc
                    on tc.constraint_schema = cc.constraint_schema
                   and tc.constraint_name = cc.constraint_name
                 where tc.table_schema = database()
                   and tc.table_name = 'notifications'
                   and tc.constraint_type = 'CHECK'
                   and (cc.check_clause like '%`type`%'
                     or cc.check_clause like '%`resource_type`%'
                     or cc.check_clause like '%`recipient_type`%')
                """, Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("컬럼이 이미 varchar인데 마커가 없으면 오타 값을 정정한다(전환 직후 부분 실패 복구)")
    void migration_correctsLegacyTypoEvenWhenColumnsAlreadyConverted() {
        // 전환 ALTER는 DDL이라 즉시 커밋된다. 그 직후 다음 단계에서 예외가 나 부팅이 실패하면 컬럼만 varchar로
        // 남고 마커는 없다. 그 상태를 재현한다 — 정정을 "방금 전환했는가"에만 묶으면 여기서 영구히 건너뛴다
        // (리뷰 지적 P1). 위 migration_correctsLegacyTypoValue는 ENUM으로 되돌린 뒤 실행해 이 경로를 못 덮는다.
        deleteMarker();
        long id = insertNotification("RESERVATION_HOSPITAL_CANCELLED", "RESERVATION");
        assertThat(columnType("type")).isEqualTo(NotificationTypeVarcharMigrationRunner.TARGET_COLUMN_TYPE);

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(typeOf(id)).isEqualTo("RESERVATION_HOSPITAL_CANCELED");
        assertThat(markerExists()).isTrue();
    }

    @Test
    @DisplayName("마커가 있고 전환도 불필요하면 정정 스캔을 반복하지 않는다")
    void migration_skipsCorrectionWhenAlreadyApplied() {
        // 정상 상태에서 매 부팅 full scan을 돌지 않는다는 계약. 오타 행을 남겨 두고도 손대지 않아야 한다
        // (마커가 있으면 이미 완주했다는 뜻이므로, 이 상태의 오타 행은 이 러너가 만든 것이 아니다).
        ensureMarker();
        long id = insertNotification("RESERVATION_HOSPITAL_CANCELLED", "RESERVATION");

        new NotificationTypeVarcharMigrationRunner(jdbcTemplate).migrateBeforeJpa();

        assertThat(typeOf(id)).isEqualTo("RESERVATION_HOSPITAL_CANCELLED");
    }

    private void revertToLegacyEnum(String typeEnum) {
        // enum에 없는 값을 쓰는 이전 테스트의 잔존 행이 있으면 ALTER 자체가 실패한다 — 먼저 지운다.
        jdbcTemplate.update("delete from notifications where content = ?", TEST_CONTENT);
        jdbcTemplate.execute("alter table notifications modify column type " + typeEnum + " not null");
        jdbcTemplate.execute(
                "alter table notifications modify column resource_type " + LEGACY_RESOURCE_TYPE_ENUM + " null");
    }

    private long insertNotification(String type, String resourceType) {
        jdbcTemplate.update("""
                insert into notifications
                    (recipient_type, recipient_id, member_id, type, content, resource_type, resource_id,
                     created_at, updated_at)
                values ('MEMBER', ?, ?, ?, ?, ?, ?, now(6), now(6))
                """, 917_600L, 917_600L, type, TEST_CONTENT, resourceType, 1L);
        return jdbcTemplate.queryForObject(
                "select max(id) from notifications where content = ?", Long.class, TEST_CONTENT);
    }

    private String typeOf(long id) {
        return jdbcTemplate.queryForObject("select type from notifications where id = ?", String.class, id);
    }

    private String resourceTypeOf(long id) {
        return jdbcTemplate.queryForObject("select resource_type from notifications where id = ?", String.class, id);
    }

    private int legacyTypoRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from notifications where type = 'RESERVATION_HOSPITAL_CANCELLED'", Integer.class);
        return count == null ? 0 : count;
    }

    private String columnType(String columnName) {
        List<String> found = jdbcTemplate.queryForList("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = ?
                """, String.class, columnName);
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean columnNullable(String columnName) {
        return "YES".equals(jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = ?
                """, String.class, columnName));
    }

    private void ensureMarker() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now(6))
                on duplicate key update migration_key = migration_key
                """, NotificationTypeVarcharMigrationRunner.MIGRATION_KEY);
        if (!markerExists()) {
            throw new IllegalStateException("마이그레이션 마커를 준비하지 못했습니다.");
        }
    }

    private void deleteMarker() {
        jdbcTemplate.update("delete from schema_migrations where migration_key = ?",
                NotificationTypeVarcharMigrationRunner.MIGRATION_KEY);
    }

    private boolean markerExists() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class,
                NotificationTypeVarcharMigrationRunner.MIGRATION_KEY);
        return count != null && count == 1;
    }
}
