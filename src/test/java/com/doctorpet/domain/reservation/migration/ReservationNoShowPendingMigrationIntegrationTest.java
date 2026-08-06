package com.doctorpet.domain.reservation.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class ReservationNoShowPendingMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("실제 MySQL에 추가 유예 컬럼과 일회성 마이그레이션 마커가 존재한다")
    void pendingColumnAndMigrationMarker_exist() {
        Integer columnCount = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservations'
                   and column_name = 'no_show_pending_at'
                   and is_nullable = 'YES'
                """, Integer.class);
        Integer markerCount = jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class, ReservationNoShowPendingMigrationRunner.MIGRATION_KEY);

        assertThat(columnCount).isEqualTo(1);
        assertThat(markerCount).isEqualTo(1);
    }
}
