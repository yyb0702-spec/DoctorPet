package com.doctorpet.domain.member.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.entity.MemberRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — 병원 스태프 조회용 members(hospital_id, role) 인덱스가 실제 MySQL에 만들어지고, fan-out 대상
 * 조회에 적용 가능한지(members 풀스캔이 아닌지) 검증한다(#166).
 *
 * <p>조회용 인덱스는 락·트랜잭션과 무관하므로 동시성 테스트 대신 이 Level 3 통합 검증으로 충분하다(AGENTS
 * STRICT 신호 예외 조항).
 *
 * <p>실행계획은 possible_keys(적용 가능한 후보)와 FORCE INDEX 결과로만 검증한다 — 강제하지 않은 최종 선택은
 * 공유 MySQL의 통계·데이터 분포에 따라 바뀌고, 행이 적은 테스트 DB에서는 옵티마이저가 풀스캔을 고르는 것이
 * 정상이라 단정하면 데이터 상태에 취약해진다({@code NotificationRepositoryIntegrationTest}와 같은 기준).
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
class MemberHospitalStaffIndexMigrationIntegrationTest {

    private static final String INDEX_NAME = MemberHospitalStaffIndexMigrationRunner.INDEX_NAME;
    private static final String STAFF_QUERY =
            "select id from members where hospital_id = ? and role = ? and deleted_at is null";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void restoreMigrationState() {
        new MemberHospitalStaffIndexMigrationRunner(jdbcTemplate).run(null);
    }

    @Test
    @DisplayName("idx_members_hospital_role이 (hospital_id, role) 순서로 생성되고 스태프 조회의 후보 인덱스가 된다")
    void hospitalStaffIndex_existsAndIsUsableByFanOutQuery() throws SQLException {
        assertThat(indexColumns()).isEqualTo("hospital_id,role");

        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + STAFF_QUERY)) {
                explain.setLong(1, 1L);
                explain.setString(2, MemberRole.HOSPITAL_STAFF.name());
                try (ResultSet rs = explain.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("possible_keys")).contains(INDEX_NAME);
                }
            }

            // 인덱스를 강제하면 실행계획이 풀스캔(type=ALL)이 아니라 ref 조회가 돼야 한다 — 이 인덱스가 조건에
            // 실제로 적용 가능하다는 뜻이다(통계와 무관하게 결정적으로 확인할 수 있는 형태).
            try (PreparedStatement explain = connection.prepareStatement(
                    "EXPLAIN select id from members force index (" + INDEX_NAME + ") "
                            + "where hospital_id = ? and role = ? and deleted_at is null")) {
                explain.setLong(1, 1L);
                explain.setString(2, MemberRole.HOSPITAL_STAFF.name());
                try (ResultSet rs = explain.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("type")).isNotEqualTo("ALL");
                    assertThat(rs.getString("key")).isEqualTo(INDEX_NAME);
                }
            }
        }
    }

    @Test
    @DisplayName("마커를 지우고 다시 실행해도 안전하고, 컬럼 구성이 다른 동명 인덱스는 올바른 정의로 교체한다")
    void migration_isIdempotentAndReplacesDriftedIndex() {
        // 컬럼 순서가 뒤바뀐 동명 인덱스를 심어 드리프트를 만든다. 같은 이름으로 create index를 다시 실행하면
        // MySQL이 Duplicate key name으로 부팅을 중단시키므로, 러너가 스스로 교체해야 한다.
        jdbcTemplate.execute("drop index " + INDEX_NAME + " on members");
        jdbcTemplate.execute("create index " + INDEX_NAME + " on members (role, hospital_id)");
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                MemberHospitalStaffIndexMigrationRunner.MIGRATION_KEY
        );
        MemberHospitalStaffIndexMigrationRunner runner =
                new MemberHospitalStaffIndexMigrationRunner(jdbcTemplate);

        runner.run(null);
        // 마커가 남은 상태의 재실행(재부팅)도 스키마를 깨지 않아야 한다.
        runner.run(null);

        assertThat(indexColumns()).isEqualTo("hospital_id,role");
        assertThat(migrationMarkerExists()).isTrue();
    }

    private String indexColumns() {
        return jdbcTemplate.queryForObject("""
                select group_concat(column_name order by seq_in_index)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'members'
                   and index_name = ?
                """, String.class, INDEX_NAME);
    }

    private boolean migrationMarkerExists() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from schema_migrations where migration_key = ?",
                Integer.class,
                MemberHospitalStaffIndexMigrationRunner.MIGRATION_KEY
        );
        return count != null && count == 1;
    }
}
