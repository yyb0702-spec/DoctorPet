package com.doctorpet.domain.hospital.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 병원 도메인의 일회성 스키마 변경을 순서대로 적용하고 검증한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
@ConditionalOnProperty(
        prefix = "hospital.schema-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class HospitalSchemaMigrationRunner implements ApplicationRunner {

    static final String CAPABILITY_VALUE_MIGRATION_KEY =
            "hospital_capability_value_varchar_v1";
    static final String SEARCH_INDEX_MIGRATION_KEY = "hospital_search_indexes_v1";
    static final String NAME_ORDER_INDEX =
            "idx_hospitals_name_id_business_status";
    static final String PARTNERSHIP_NAME_ORDER_INDEX =
            "idx_hospitals_partnership_name_id_business_status";
    static final String DEPRECATED_NAME_ORDER_INDEX = "idx_hospitals_name_id";
    static final String DEPRECATED_BUSINESS_STATUS_INDEX =
            "idx_hospitals_business_status";
    static final String DEPRECATED_PARTNERSHIP_NAME_ORDER_INDEX =
            "idx_hospitals_partnership_name_id";
    static final String DEPRECATED_COORDINATE_INDEX =
            "idx_hospitals_coord_y_x";
    static final String DEPRECATED_CAPABILITY_INDEX =
            "idx_hospital_capabilities_type_value_hospital";
    static final String DEPRECATED_CAPABILITY_VALUE_INDEX =
            "idx_hospital_capabilities_value_hospital";
    static final String COORDINATE_INDEX = "idx_hospitals_coord_x_y";

    private static final String LOCK_NAME = "doctorpet:hospital_schema_migrations";
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    private static final int CAPABILITY_VALUE_LENGTH = 32;
    static final List<IndexDefinition> SEARCH_INDEXES = List.of(
            new IndexDefinition(
                    "hospitals",
                    NAME_ORDER_INDEX,
                    "name,id,business_status",
                    "create index idx_hospitals_name_id_business_status "
                            + "on hospitals (name, id, business_status)"
            ),
            new IndexDefinition(
                    "hospitals",
                    PARTNERSHIP_NAME_ORDER_INDEX,
                    "partnership_status,name,id,business_status",
                    "create index "
                            + "idx_hospitals_partnership_name_id_business_status "
                            + "on hospitals "
                            + "(partnership_status, name, id, business_status)"
            ),
            new IndexDefinition(
                    "hospitals",
                    COORDINATE_INDEX,
                    "coord_x,coord_y",
                    "create index idx_hospitals_coord_x_y "
                            + "on hospitals (coord_x, coord_y)"
            )
    );

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            acquireLock(connection);
            try {
                migrateCapabilityValue(connection);
                migrateSearchIndexes(connection);
            } finally {
                releaseLock(connection);
            }
            return null;
        });
    }

    private void migrateCapabilityValue(Connection connection) throws SQLException {
        if (migrationApplied(connection, CAPABILITY_VALUE_MIGRATION_KEY)) {
            assertCapabilityValueColumn(connection);
            return;
        }

        assertExistingCapabilityValuesFit(connection);
        if (!capabilityValueColumnIsVarchar(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table hospital_capabilities
                        modify column capability_value varchar(32) not null
                        """);
            }
        }

        assertCapabilityValueColumn(connection);
        recordMigration(connection, CAPABILITY_VALUE_MIGRATION_KEY);
        log.info("hospital_capabilities.capability_value VARCHAR(32) 마이그레이션 완료");
    }

    private void migrateSearchIndexes(Connection connection) throws SQLException {
        if (migrationApplied(connection, SEARCH_INDEX_MIGRATION_KEY)) {
            assertSearchIndexes(connection);
            assertDeprecatedIndexRemoved(connection);
            return;
        }

        dropIndexIfExists(
                connection,
                "hospitals",
                DEPRECATED_BUSINESS_STATUS_INDEX
        );
        dropIndexIfExists(
                connection,
                "hospitals",
                DEPRECATED_NAME_ORDER_INDEX
        );
        dropIndexIfExists(
                connection,
                "hospitals",
                DEPRECATED_PARTNERSHIP_NAME_ORDER_INDEX
        );
        dropIndexIfExists(
                connection,
                "hospitals",
                DEPRECATED_COORDINATE_INDEX
        );
        dropIndexIfExists(
                connection,
                "hospital_capabilities",
                DEPRECATED_CAPABILITY_INDEX
        );
        dropIndexIfExists(
                connection,
                "hospital_capabilities",
                DEPRECATED_CAPABILITY_VALUE_INDEX
        );
        for (IndexDefinition index : SEARCH_INDEXES) {
            repairOrCreateIndex(connection, index);
        }

        assertSearchIndexes(connection);
        assertDeprecatedIndexRemoved(connection);
        recordMigration(connection, SEARCH_INDEX_MIGRATION_KEY);
        log.info("병원 검색 인덱스 마이그레이션 완료: {}, {}, {}",
                NAME_ORDER_INDEX,
                PARTNERSHIP_NAME_ORDER_INDEX,
                COORDINATE_INDEX);
    }

    private void repairOrCreateIndex(
            Connection connection,
            IndexDefinition index
    ) throws SQLException {
        if (indexExists(connection, index)) {
            return;
        }

        if (indexNameExists(connection, index.table(), index.name())) {
            if (indexColumnsMatch(connection, index)) {
                makeIndexVisible(connection, index);
                return;
            }
            dropIndexIfExists(connection, index.table(), index.name());
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(index.createSql());
        }
    }

    private void makeIndexVisible(
            Connection connection,
            IndexDefinition index
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "alter table " + index.table()
                            + " alter index " + index.name() + " visible"
            );
        }
    }

    private void assertExistingCapabilityValuesFit(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select max(char_length(capability_value))
                       from hospital_capabilities
                     """)) {
            if (resultSet.next()
                    && resultSet.getInt(1) > CAPABILITY_VALUE_LENGTH) {
                throw new IllegalStateException(
                        "병원 역량 값이 VARCHAR(32) 허용 길이를 초과합니다."
                );
            }
        }
    }

    private void assertCapabilityValueColumn(Connection connection)
            throws SQLException {
        if (!capabilityValueColumnIsVarchar(connection)) {
            throw new IllegalStateException(
                    "hospital_capabilities.capability_value가 VARCHAR(32)가 아닙니다."
            );
        }
    }

    private boolean capabilityValueColumnIsVarchar(Connection connection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select data_type, character_maximum_length, is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'hospital_capabilities'
                   and column_name = 'capability_value'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && "varchar".equalsIgnoreCase(resultSet.getString("data_type"))
                        && resultSet.getInt("character_maximum_length")
                        == CAPABILITY_VALUE_LENGTH
                        && "NO".equals(resultSet.getString("is_nullable"));
            }
        }
    }

    private void assertSearchIndexes(Connection connection) throws SQLException {
        for (IndexDefinition index : SEARCH_INDEXES) {
            if (!indexExists(connection, index)) {
                throw new IllegalStateException(
                        "병원 검색 인덱스가 없거나 컬럼 순서가 다릅니다: " + index.name()
                );
            }
        }
    }

    private void assertDeprecatedIndexRemoved(Connection connection)
            throws SQLException {
        List<String> remainingIndexes = deprecatedIndexesPresent(connection);
        if (!remainingIndexes.isEmpty()) {
            throw new IllegalStateException(
                    "제거 대상 병원 검색 인덱스가 남아 있습니다: "
                            + String.join(", ", remainingIndexes)
                            + ". 제거 명령: "
                            + removalCommands(remainingIndexes)
            );
        }
    }

    private String removalCommands(List<String> remainingIndexes) {
        return remainingIndexes.stream()
                .map(index -> {
                    String[] qualifiedName = index.split("\\.", 2);
                    return "alter table " + qualifiedName[0]
                            + " drop index " + qualifiedName[1] + ";";
                })
                .collect(Collectors.joining(" "));
    }

    private List<String> deprecatedIndexesPresent(Connection connection)
            throws SQLException {
        List<String> remainingIndexes = new ArrayList<>();
        addIfIndexExists(
                connection,
                remainingIndexes,
                "hospitals",
                DEPRECATED_BUSINESS_STATUS_INDEX
        );
        addIfIndexExists(
                connection,
                remainingIndexes,
                "hospitals",
                DEPRECATED_NAME_ORDER_INDEX
        );
        addIfIndexExists(
                connection,
                remainingIndexes,
                "hospitals",
                DEPRECATED_PARTNERSHIP_NAME_ORDER_INDEX
        );
        addIfIndexExists(
                connection,
                remainingIndexes,
                "hospitals",
                DEPRECATED_COORDINATE_INDEX
        );
        addIfIndexExists(
                connection,
                remainingIndexes,
                "hospital_capabilities",
                DEPRECATED_CAPABILITY_INDEX
        );
        addIfIndexExists(
                connection,
                remainingIndexes,
                "hospital_capabilities",
                DEPRECATED_CAPABILITY_VALUE_INDEX
        );
        return remainingIndexes;
    }

    private void addIfIndexExists(
            Connection connection,
            List<String> remainingIndexes,
            String table,
            String indexName
    ) throws SQLException {
        if (indexNameExists(connection, table, indexName)) {
            remainingIndexes.add(table + "." + indexName);
        }
    }

    private void dropIndexIfExists(
            Connection connection,
            String table,
            String indexName
    ) throws SQLException {
        if (!indexNameExists(connection, table, indexName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "alter table " + table + " drop index " + indexName
            );
        }
    }

    private boolean indexNameExists(
            Connection connection,
            String table,
            String indexName
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = ?
                   and index_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean indexExists(Connection connection, IndexDefinition index)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
                """)) {
            statement.setString(1, index.table());
            statement.setString(2, index.name());
            statement.setString(3, index.columns());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean indexColumnsMatch(
            Connection connection,
            IndexDefinition index
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
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
                """)) {
            statement.setString(1, index.table());
            statement.setString(2, index.name());
            statement.setString(3, index.columns());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private boolean migrationApplied(Connection connection, String migrationKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """)) {
            statement.setString(1, migrationKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private void recordMigration(Connection connection, String migrationKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                on duplicate key update migration_key = migration_key
                """)) {
            statement.setString(1, migrationKey);
            statement.executeUpdate();
        }
    }

    private void acquireLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select get_lock(?, ?)"
        )) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "병원 스키마 마이그레이션 DB 잠금을 획득하지 못했습니다."
                    );
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select release_lock(?)"
        )) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    log.warn("병원 스키마 마이그레이션 DB 잠금이 해제되지 않았습니다.");
                }
            }
        } catch (SQLException exception) {
            log.warn("병원 스키마 마이그레이션 DB 잠금 해제에 실패했습니다.", exception);
        }
    }

}
