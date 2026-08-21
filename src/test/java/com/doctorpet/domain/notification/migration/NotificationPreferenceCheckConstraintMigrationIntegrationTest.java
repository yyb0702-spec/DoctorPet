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
 * Level 3 — 수신 설정 테이블의 값 열거 CHECK 제약을 러너가 드롭하는지 실제 MySQL로 검증한다(고도화 3.9).
 *
 * <p>Hibernate는 `@Enumerated(EnumType.STRING)` 컬럼을 VARCHAR로 만들 때 허용 값을 열거하는 CHECK 제약을 함께
 * 생성한다. 이 테이블은 신규라 <b>모든 DB에서 새로 생성</b>되므로 예외 없이 붙고, 남겨두면 알림 유형·채널을 추가할
 * 때마다 이 테이블에 DDL이 필요해져 이슈 #176이 없앤 부채가 새 테이블로 되살아난다.
 *
 * <p>테스트는 제약을 <b>직접 만들어 놓고</b> 러너가 드롭하는지 본다 — 부팅 시 이미 드롭된 상태라 그냥 조회하면
 * 러너를 지워도 통과하기 때문이다(드리프트 재현이 곧 회귀 검출이다).
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
class NotificationPreferenceCheckConstraintMigrationIntegrationTest {

    private static final String TEST_CONSTRAINT = "chk_test_pref_notification_type";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void dropLeftoverConstraint() {
        // 테스트가 실패해 러너까지 도달하지 못한 경우에도 다음 테스트의 시작 상태를 고정한다.
        if (!checkConstraintNames().isEmpty()) {
            new NotificationPreferenceCheckConstraintMigrationRunner(jdbcTemplate).migrate();
        }
    }

    @Test
    @DisplayName("부팅 직후에는 값 열거 CHECK 제약이 없다")
    void afterStartup_noCheckConstraintRemains() {
        assertThat(checkConstraintNames()).isEmpty();
    }

    @Test
    @DisplayName("CHECK 제약이 있으면 드롭하고, 드롭 후에는 열거에 없는 값도 저장된다")
    void migrate_dropsCheckConstraintSoNewValuesInsert() {
        // Hibernate가 테이블 생성 시 붙이는 것과 같은 형태를 재현한다(현재 enum 값만 허용).
        jdbcTemplate.execute("alter table notification_preferences add constraint " + TEST_CONSTRAINT
                + " check (`notification_type` in ('RESERVATION_CONFIRMED','PAYMENT_RESULT'))");
        assertThat(checkConstraintNames()).contains(TEST_CONSTRAINT);

        new NotificationPreferenceCheckConstraintMigrationRunner(jdbcTemplate).migrate();

        assertThat(checkConstraintNames()).isEmpty();
        // 제약이 살아 있으면 이 INSERT가 MySQL 3819로 실패한다 — 유형 추가에 DDL이 필요했다는 증거다.
        assertThatCode(() -> insertPreference("FUTURE_TYPE_NOT_IN_ENUM")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("제약이 없을 때 다시 실행해도 안전하다(멱등)")
    void migrate_isIdempotent() {
        NotificationPreferenceCheckConstraintMigrationRunner runner =
                new NotificationPreferenceCheckConstraintMigrationRunner(jdbcTemplate);

        runner.migrate();
        runner.migrate();

        assertThat(checkConstraintNames()).isEmpty();
        assertThat(markerExists()).isTrue();
    }

    private void insertPreference(String notificationType) {
        long memberId = Math.abs(System.nanoTime());
        jdbcTemplate.update("""
                insert into notification_preferences
                    (member_id, notification_type, channel, enabled, created_at, updated_at)
                values (?, ?, 'EMAIL', false, now(6), now(6))
                """, memberId, notificationType);
        jdbcTemplate.update("delete from notification_preferences where member_id = ?", memberId);
    }

    // 러너가 드롭 대상을 고르는 기준과 같은 조건으로 조회한다(이름으로 세지 않는다).
    private List<String> checkConstraintNames() {
        return jdbcTemplate.queryForList("""
                select tc.constraint_name
                  from information_schema.table_constraints tc
                  join information_schema.check_constraints cc
                    on tc.constraint_schema = cc.constraint_schema
                   and tc.constraint_name = cc.constraint_name
                 where tc.table_schema = database()
                   and tc.table_name = 'notification_preferences'
                   and tc.constraint_type = 'CHECK'
                   and (cc.check_clause like '%`notification_type`%'
                     or cc.check_clause like '%`channel`%')
                """, String.class);
    }

    private boolean markerExists() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class,
                NotificationPreferenceCheckConstraintMigrationRunner.MIGRATION_KEY);
        return count != null && count == 1;
    }
}
