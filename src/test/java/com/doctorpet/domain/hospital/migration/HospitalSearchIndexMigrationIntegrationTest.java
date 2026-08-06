package com.doctorpet.domain.hospital.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 실제 MySQL에서 병원 검색 인덱스 마이그레이션을 검증한다. */
@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "reservation.event-unique-migration.enabled=false",
        "reservation.approval-deadline-migration.enabled=false",
        "hospital.schema-migration.enabled=false"
})
class HospitalSearchIndexMigrationIntegrationTest {

    private static final List<IndexSpec> INDEXES = List.of(
            new IndexSpec(
                    "hospitals",
                    HospitalSchemaMigrationRunner.NAME_ORDER_INDEX,
                    "name,id"
            ),
            new IndexSpec(
                    "hospitals",
                    HospitalSchemaMigrationRunner.COORDINATE_INDEX,
                    "coord_x,coord_y"
            ),
            new IndexSpec(
                    "hospital_capabilities",
                    HospitalSchemaMigrationRunner.CAPABILITY_INDEX,
                    "capability_type,capability_value,hospital_id"
            )
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void removeMigrationState() {
        deleteMarker();
        INDEXES.forEach(this::dropIndex);
        createDeprecatedBusinessStatusIndex();
    }

    @AfterEach
    void restoreSchema() {
        dropDeprecatedBusinessStatusIndex();
        INDEXES.forEach(this::createIndex);
        deleteMarker();
    }

    @Test
    @DisplayName("검색 인덱스를 컬럼 순서대로 생성하고 재실행을 방지한다")
    void createsSearchIndexesAndMigrationMarkerIdempotently() throws Exception {
        HospitalSchemaMigrationRunner runner =
                new HospitalSchemaMigrationRunner(jdbcTemplate);

        runner.run(null);
        runner.run(null);

        assertThat(INDEXES).allMatch(this::indexExists);
        assertThat(deprecatedBusinessStatusIndexExists()).isFalse();
        assertThat(markerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("마이그레이션 마커와 실제 인덱스가 다르면 부팅 오류로 처리한다")
    void appliedMarkerWithoutIndexesFailsFast() {
        insertMarker();

        assertThatThrownBy(() ->
                new HospitalSchemaMigrationRunner(jdbcTemplate).run(null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("병원 검색 인덱스");
    }

    @Test
    @DisplayName("마커 적용 후 제거 대상 인덱스가 다시 생기면 부팅 오류로 처리한다")
    void appliedMarkerWithDeprecatedIndexFailsFast() {
        INDEXES.forEach(this::createIndex);
        insertMarker();

        assertThatThrownBy(() ->
                new HospitalSchemaMigrationRunner(jdbcTemplate).run(null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("제거 대상 병원 검색 인덱스");
    }

    private boolean indexExists(IndexSpec index) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from (
                        select index_name
                          from information_schema.statistics
                         where table_schema = database()
                           and table_name = ?
                           and index_name = ?
                         group by index_name
                        having group_concat(column_name order by seq_in_index) = ?
                       ) matching_index
                """, Integer.class, index.table(), index.name(), index.columns());
        return count != null && count > 0;
    }

    private void dropIndex(IndexSpec index) {
        if (indexExists(index)) {
            jdbcTemplate.execute(
                    "alter table " + index.table() + " drop index " + index.name()
            );
        }
    }

    private void createIndex(IndexSpec index) {
        if (!indexExists(index)) {
            jdbcTemplate.execute(
                    "create index " + index.name() + " on " + index.table()
                            + " (" + index.columns() + ")"
            );
        }
    }

    private void insertMarker() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                """, HospitalSchemaMigrationRunner.SEARCH_INDEX_MIGRATION_KEY);
    }

    private void createDeprecatedBusinessStatusIndex() {
        if (!deprecatedBusinessStatusIndexExists()) {
            jdbcTemplate.execute(
                    "create index "
                            + HospitalSchemaMigrationRunner
                            .DEPRECATED_BUSINESS_STATUS_INDEX
                            + " on hospitals (business_status)"
            );
        }
    }

    private void dropDeprecatedBusinessStatusIndex() {
        if (deprecatedBusinessStatusIndexExists()) {
            jdbcTemplate.execute(
                    "alter table hospitals drop index "
                            + HospitalSchemaMigrationRunner
                            .DEPRECATED_BUSINESS_STATUS_INDEX
            );
        }
    }

    private boolean deprecatedBusinessStatusIndexExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'hospitals'
                   and index_name = ?
                """, Integer.class, HospitalSchemaMigrationRunner
                .DEPRECATED_BUSINESS_STATUS_INDEX);
        return count != null && count > 0;
    }

    private void deleteMarker() {
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                HospitalSchemaMigrationRunner.SEARCH_INDEX_MIGRATION_KEY
        );
    }

    private int markerCount() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class,
                HospitalSchemaMigrationRunner.SEARCH_INDEX_MIGRATION_KEY);
        return count == null ? 0 : count;
    }

    private record IndexSpec(String table, String name, String columns) {
    }
}
