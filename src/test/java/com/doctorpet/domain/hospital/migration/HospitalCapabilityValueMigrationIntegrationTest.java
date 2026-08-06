package com.doctorpet.domain.hospital.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
class HospitalCapabilityValueMigrationIntegrationTest {

    private static final String LEGACY_ENUM_DEFINITION = """
            enum(
                'DOG','CAT','BIRD','RABBIT','HAMSTER','GUINEA_PIG','FERRET','REPTILE',
                'BLOOD_TEST','XRAY','ULTRASOUND','ORTHOPEDIC_CARE','DENTAL_CARE',
                'OPHTHALMIC_CARE','REHABILITATION','ONCOLOGY_CARE','CT','MRI','ENDOSCOPE'
            )
            """;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HospitalRepository hospitalRepository;

    private Long hospitalId;
    private boolean searchIndexMarkerExisted;

    @BeforeEach
    void prepareLegacySchema() {
        searchIndexMarkerExisted = searchIndexMarkerCount() > 0;
        if (!searchIndexMarkerExisted) {
            insertSearchIndexMarker();
        }
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                HospitalSchemaMigrationRunner.CAPABILITY_VALUE_MIGRATION_KEY
        );
        Hospital hospital = hospitalRepository.saveAndFlush(
                Hospital.createFromPublicData(
                        "CAPABILITY-MIGRATION-TEST",
                        "TEST-LOCAL-GOV",
                        "역량 마이그레이션 테스트 병원",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        BusinessStatus.OPEN,
                        null,
                        null,
                        null
                )
        );
        hospitalId = hospital.getId();
        jdbcTemplate.execute(
                "alter table hospital_capabilities "
                        + "modify column capability_value "
                        + LEGACY_ENUM_DEFINITION
                        + " not null"
        );
        insertCapability("DOG", "SPECIES");
        insertCapability("BLOOD_TEST", "EXAM");
    }

    @AfterEach
    void restoreSchema() {
        assertThat(searchIndexMarkerCount()).isEqualTo(1);
        jdbcTemplate.update(
                "delete from hospital_capabilities where hospital_id = ?",
                hospitalId
        );
        jdbcTemplate.execute("""
                alter table hospital_capabilities
                modify column capability_value varchar(32) not null
                """);
        hospitalRepository.deleteById(hospitalId);
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                HospitalSchemaMigrationRunner.CAPABILITY_VALUE_MIGRATION_KEY
        );
        if (!searchIndexMarkerExisted) {
            deleteSearchIndexMarker();
        }
    }

    @Test
    @DisplayName("기존 ENUM 데이터를 보존하며 VARCHAR(32)로 전환하고 재실행을 방지한다")
    void legacyEnum_isMigratedToVarcharWithoutDataLoss() throws Exception {
        HospitalSchemaMigrationRunner runner =
                new HospitalSchemaMigrationRunner(jdbcTemplate);

        runner.run(null);
        runner.run(null);

        assertThat(columnDataType()).isEqualTo("varchar");
        assertThat(columnLength()).isEqualTo(32);
        assertThat(capabilityValues()).containsExactlyInAnyOrder("DOG", "BLOOD_TEST");
        assertThat(migrationMarkerCount()).isEqualTo(1);

        insertCapability("BIRD", "SPECIES");
        assertThat(capabilityValues()).contains("BIRD");
    }

    @Test
    @DisplayName("마이그레이션 마커와 실제 컬럼 타입이 다르면 부팅 오류로 처리한다")
    void appliedMarkerWithLegacyEnum_failsFast() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                """, HospitalSchemaMigrationRunner.CAPABILITY_VALUE_MIGRATION_KEY);

        assertThatThrownBy(() ->
                new HospitalSchemaMigrationRunner(jdbcTemplate).run(null)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VARCHAR(32)");
    }

    private void insertCapability(String value, String type) {
        jdbcTemplate.update("""
                insert into hospital_capabilities
                    (hospital_id, capability_type, capability_value)
                values (?, ?, ?)
                """, hospitalId, type, value);
    }

    private String columnDataType() {
        return jdbcTemplate.queryForObject("""
                select data_type
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'hospital_capabilities'
                   and column_name = 'capability_value'
                """, String.class);
    }

    private Integer columnLength() {
        return jdbcTemplate.queryForObject("""
                select character_maximum_length
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'hospital_capabilities'
                   and column_name = 'capability_value'
                """, Integer.class);
    }

    private List<String> capabilityValues() {
        return jdbcTemplate.queryForList("""
                select capability_value
                  from hospital_capabilities
                 where hospital_id = ?
                 order by capability_value
                """, String.class, hospitalId);
    }

    private Integer migrationMarkerCount() {
        return jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class,
                HospitalSchemaMigrationRunner.CAPABILITY_VALUE_MIGRATION_KEY);
    }

    private int searchIndexMarkerCount() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class,
                HospitalSchemaMigrationRunner.SEARCH_INDEX_MIGRATION_KEY);
        return count == null ? 0 : count;
    }

    private void insertSearchIndexMarker() {
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                """, HospitalSchemaMigrationRunner.SEARCH_INDEX_MIGRATION_KEY);
    }

    private void deleteSearchIndexMarker() {
        jdbcTemplate.update(
                "delete from schema_migrations where migration_key = ?",
                HospitalSchemaMigrationRunner.SEARCH_INDEX_MIGRATION_KEY
        );
    }
}
