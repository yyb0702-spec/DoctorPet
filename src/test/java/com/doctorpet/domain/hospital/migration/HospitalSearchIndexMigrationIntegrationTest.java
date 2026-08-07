package com.doctorpet.domain.hospital.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.hospital.entity.Hospital;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
                    "name,id,business_status"
            ),
            new IndexSpec(
                    "hospitals",
                    HospitalSchemaMigrationRunner.PARTNERSHIP_NAME_ORDER_INDEX,
                    "partnership_status,name,id,business_status"
            ),
            new IndexSpec(
                    "hospitals",
                    HospitalSchemaMigrationRunner.COORDINATE_INDEX,
                    "coord_x,coord_y"
            )
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void removeMigrationState() {
        deleteMarker();
        INDEXES.forEach(this::dropIndexByName);
        createDeprecatedBusinessStatusIndex();
        createDeprecatedNameOrderIndex();
        createDeprecatedPartnershipNameOrderIndex();
        createDeprecatedCoordinateIndex();
        createDeprecatedCapabilityIndex();
        createDeprecatedCapabilityValueIndex();
    }

    @AfterEach
    void restoreSchema() {
        dropDeprecatedBusinessStatusIndex();
        dropDeprecatedNameOrderIndex();
        dropDeprecatedPartnershipNameOrderIndex();
        dropDeprecatedCoordinateIndex();
        dropDeprecatedCapabilityIndex();
        dropDeprecatedCapabilityValueIndex();
        INDEXES.forEach(this::restoreIndex);
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
        assertThat(deprecatedNameOrderIndexExists()).isFalse();
        assertThat(deprecatedPartnershipNameOrderIndexExists()).isFalse();
        assertThat(deprecatedCoordinateIndexExists()).isFalse();
        assertThat(deprecatedCapabilityIndexExists()).isFalse();
        assertThat(deprecatedCapabilityValueIndexExists()).isFalse();
        assertThat(markerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 이름의 잘못된 인덱스를 올바른 컬럼 순서로 교체한다")
    void replacesSameNameIndexWithWrongColumns() throws Exception {
        IndexSpec target = INDEXES.get(0);
        createIndex(target.table(), target.name(), "business_status,name,id");

        new HospitalSchemaMigrationRunner(jdbcTemplate).run(null);

        assertThat(INDEXES).allMatch(this::indexExists);
        assertThat(markerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 정의의 INVISIBLE 인덱스를 VISIBLE로 복구한다")
    void makesMatchingInvisibleIndexVisible() throws Exception {
        INDEXES.forEach(this::createIndex);
        IndexSpec target = INDEXES.get(0);
        jdbcTemplate.execute(
                "alter table " + target.table()
                        + " alter index " + target.name() + " invisible"
        );

        new HospitalSchemaMigrationRunner(jdbcTemplate).run(null);

        assertThat(INDEXES).allMatch(this::indexExists);
        assertThat(markerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("엔티티와 마이그레이션의 병원 인덱스 정의가 일치한다")
    void entityAndMigrationIndexDefinitionsMatch() {
        Map<String, String> entityIndexes = Arrays.stream(
                        Hospital.class.getAnnotation(Table.class).indexes()
                )
                .collect(Collectors.toMap(
                        jakarta.persistence.Index::name,
                        index -> normalizeColumns(index.columnList())
                ));
        Map<String, String> migrationIndexes = HospitalSchemaMigrationRunner
                .SEARCH_INDEXES
                .stream()
                .collect(Collectors.toMap(
                        IndexDefinition::name,
                        index -> normalizeColumns(index.columns())
                ));

        assertThat(entityIndexes).isEqualTo(migrationIndexes);
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
                .hasMessageContaining("제거 대상 병원 검색 인덱스")
                .hasMessageContaining(
                        "hospitals."
                                + HospitalSchemaMigrationRunner
                                .DEPRECATED_BUSINESS_STATUS_INDEX
                )
                .hasMessageContaining(
                        "alter table hospitals drop index "
                                + HospitalSchemaMigrationRunner
                                .DEPRECATED_BUSINESS_STATUS_INDEX
                                + ";"
                );
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
                           and min(is_visible) = 'YES'
                       ) matching_index
                """, Integer.class, index.table(), index.name(), index.columns());
        return count != null && count > 0;
    }

    private void dropIndexByName(IndexSpec index) {
        if (indexNameExists(index)) {
            jdbcTemplate.execute(
                    "alter table " + index.table() + " drop index " + index.name()
            );
        }
    }

    private void createIndex(IndexSpec index) {
        createIndex(index.table(), index.name(), index.columns());
    }

    private void createIndex(String table, String name, String columns) {
        jdbcTemplate.execute(
                "create index " + name + " on " + table
                        + " (" + columns + ")"
        );
    }

    private void restoreIndex(IndexSpec index) {
        dropIndexByName(index);
        createIndex(index);
    }

    private boolean indexNameExists(IndexSpec index) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = ?
                   and index_name = ?
                """, Integer.class, index.table(), index.name());
        return count != null && count > 0;
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

    private void createDeprecatedNameOrderIndex() {
        if (!deprecatedNameOrderIndexExists()) {
            jdbcTemplate.execute(
                    "create index "
                            + HospitalSchemaMigrationRunner
                            .DEPRECATED_NAME_ORDER_INDEX
                            + " on hospitals (name, id)"
            );
        }
    }

    private void dropDeprecatedNameOrderIndex() {
        if (deprecatedNameOrderIndexExists()) {
            jdbcTemplate.execute(
                    "alter table hospitals drop index "
                            + HospitalSchemaMigrationRunner
                            .DEPRECATED_NAME_ORDER_INDEX
            );
        }
    }

    private boolean deprecatedNameOrderIndexExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'hospitals'
                   and index_name = ?
                """, Integer.class, HospitalSchemaMigrationRunner
                .DEPRECATED_NAME_ORDER_INDEX);
        return count != null && count > 0;
    }

    private void createDeprecatedPartnershipNameOrderIndex() {
        if (!deprecatedPartnershipNameOrderIndexExists()) {
            jdbcTemplate.execute(
                    "create index "
                            + HospitalSchemaMigrationRunner
                            .DEPRECATED_PARTNERSHIP_NAME_ORDER_INDEX
                            + " on hospitals (partnership_status, name, id)"
            );
        }
    }

    private void dropDeprecatedPartnershipNameOrderIndex() {
        if (deprecatedPartnershipNameOrderIndexExists()) {
            jdbcTemplate.execute(
                    "alter table hospitals drop index "
                            + HospitalSchemaMigrationRunner
                            .DEPRECATED_PARTNERSHIP_NAME_ORDER_INDEX
            );
        }
    }

    private boolean deprecatedPartnershipNameOrderIndexExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'hospitals'
                   and index_name = ?
                """, Integer.class, HospitalSchemaMigrationRunner
                .DEPRECATED_PARTNERSHIP_NAME_ORDER_INDEX);
        return count != null && count > 0;
    }

    private void createDeprecatedCoordinateIndex() {
        createDeprecatedIndex(
                "hospitals",
                HospitalSchemaMigrationRunner.DEPRECATED_COORDINATE_INDEX,
                "coord_y, coord_x"
        );
    }

    private void dropDeprecatedCoordinateIndex() {
        dropDeprecatedIndex(
                "hospitals",
                HospitalSchemaMigrationRunner.DEPRECATED_COORDINATE_INDEX
        );
    }

    private boolean deprecatedCoordinateIndexExists() {
        return deprecatedIndexExists(
                "hospitals",
                HospitalSchemaMigrationRunner.DEPRECATED_COORDINATE_INDEX
        );
    }

    private void createDeprecatedCapabilityIndex() {
        createDeprecatedIndex(
                "hospital_capabilities",
                HospitalSchemaMigrationRunner.DEPRECATED_CAPABILITY_INDEX,
                "capability_type, capability_value, hospital_id"
        );
    }

    private void dropDeprecatedCapabilityIndex() {
        dropDeprecatedIndex(
                "hospital_capabilities",
                HospitalSchemaMigrationRunner.DEPRECATED_CAPABILITY_INDEX
        );
    }

    private boolean deprecatedCapabilityIndexExists() {
        return deprecatedIndexExists(
                "hospital_capabilities",
                HospitalSchemaMigrationRunner.DEPRECATED_CAPABILITY_INDEX
        );
    }

    private void createDeprecatedCapabilityValueIndex() {
        createDeprecatedIndex(
                "hospital_capabilities",
                HospitalSchemaMigrationRunner.DEPRECATED_CAPABILITY_VALUE_INDEX,
                "capability_value, hospital_id"
        );
    }

    private void dropDeprecatedCapabilityValueIndex() {
        dropDeprecatedIndex(
                "hospital_capabilities",
                HospitalSchemaMigrationRunner.DEPRECATED_CAPABILITY_VALUE_INDEX
        );
    }

    private boolean deprecatedCapabilityValueIndexExists() {
        return deprecatedIndexExists(
                "hospital_capabilities",
                HospitalSchemaMigrationRunner.DEPRECATED_CAPABILITY_VALUE_INDEX
        );
    }

    private void createDeprecatedIndex(
            String table,
            String indexName,
            String columns
    ) {
        if (!deprecatedIndexExists(table, indexName)) {
            jdbcTemplate.execute(
                    "create index " + indexName + " on " + table
                            + " (" + columns + ")"
            );
        }
    }

    private void dropDeprecatedIndex(String table, String indexName) {
        if (deprecatedIndexExists(table, indexName)) {
            jdbcTemplate.execute(
                    "alter table " + table + " drop index " + indexName
            );
        }
    }

    private boolean deprecatedIndexExists(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = ?
                   and index_name = ?
                """, Integer.class, table, indexName);
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

    private String normalizeColumns(String columns) {
        return columns.replaceAll("\\s+", "");
    }

    private record IndexSpec(String table, String name, String columns) {
    }
}
