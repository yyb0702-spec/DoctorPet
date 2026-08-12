package com.doctorpet.domain.reservation.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "reservation.slot-business-date-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationSlotBusinessDateMigrationRunner implements ApplicationRunner {

    static final String COLUMN_MIGRATION_KEY = "reservation_slot_business_date_v1";
    static final String OVERNIGHT_BACKFILL_MIGRATION_KEY =
            "reservation_slot_overnight_business_date_v2";
    private static final String LOCK_NAME = "doctorpet:reservation_slot_business_date_v1";
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    // open_hours의 openTime/closeTime은 "HH:MM" 문자열 하나다(배열 아님, PartnerHospitalOperatingHoursSeedData
    // 참조). json_extract(...)[0]로 배열 인덱싱을 하면 스칼라 문자열도 [0]엔 그 값 전체가 그대로 매칭되어
    // maketime("09:00", ...)에 문자열을 그대로 넘기게 되고, strict SQL 모드에서 "Truncated incorrect INTEGER
    // value"로 예외가 난다(리뷰 지적 — 로컬 실 데이터로 재현). "HH:MM"을 TIME으로 직접 캐스팅해 고쳤다.
    private static final String OVERNIGHT_SLOT_PREDICATE = """
            slot.business_date = date(slot.start_at)
              and json_extract(
                      detail.open_hours,
                      concat('$.' ,
                             elt(weekday(date_sub(date(slot.start_at), interval 1 day)) + 1,
                                 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                                 'FRIDAY', 'SATURDAY', 'SUNDAY'),
                             '.openTime')
                  ) is not null
              and cast(json_unquote(json_extract(
                      detail.open_hours,
                      concat('$.' ,
                             elt(weekday(date_sub(date(slot.start_at), interval 1 day)) + 1,
                                 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                                 'FRIDAY', 'SATURDAY', 'SUNDAY'),
                             '.openTime'))) as time)
                  > cast(json_unquote(json_extract(
                      detail.open_hours,
                      concat('$.' ,
                             elt(weekday(date_sub(date(slot.start_at), interval 1 day)) + 1,
                                 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                                 'FRIDAY', 'SATURDAY', 'SUNDAY'),
                             '.closeTime'))) as time)
              and time(slot.start_at) < cast(json_unquote(json_extract(
                      detail.open_hours,
                      concat('$.' ,
                             elt(weekday(date_sub(date(slot.start_at), interval 1 day)) + 1,
                                 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY',
                                 'FRIDAY', 'SATURDAY', 'SUNDAY'),
                             '.closeTime'))) as time)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            acquireLock(connection);
            try {
                migrate(connection);
            } finally {
                releaseLock(connection);
            }
            return null;
        });
    }

    private void migrate(Connection connection) throws SQLException {
        if (!migrationApplied(connection, COLUMN_MIGRATION_KEY)) {
            addColumnIfMissing(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        update reservation_slots
                           set business_date = date(start_at)
                         where business_date is null
                        """);
            }
            applyNotNull(connection);
            assertTargetSchema(connection);
            recordMigration(connection, COLUMN_MIGRATION_KEY);
        }

        assertTargetSchema(connection);
        if (!migrationApplied(connection, OVERNIGHT_BACKFILL_MIGRATION_KEY)) {
            int corrected = correctOvernightBusinessDates(connection);
            assertOvernightBusinessDates(connection);
            recordMigration(connection, OVERNIGHT_BACKFILL_MIGRATION_KEY);
            log.info("야간 예약 슬롯 영업 기준일 백필 완료: corrected={}", corrected);
        }
    }

    private int correctOvernightBusinessDates(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("""
                    update reservation_slots slot
                    join hospital_details detail
                      on detail.hospital_id = slot.hospital_id
                       set slot.business_date = date_sub(date(slot.start_at), interval 1 day)
                    """ + "where " + OVERNIGHT_SLOT_PREDICATE);
        }
    }

    private void assertOvernightBusinessDates(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*)
                       from reservation_slots slot
                       join hospital_details detail
                         on detail.hospital_id = slot.hospital_id
                     """ + "where " + OVERNIGHT_SLOT_PREDICATE)) {
            if (!resultSet.next() || resultSet.getInt(1) > 0) {
                throw new IllegalStateException(
                        "전날 영업일로 보정되지 않은 야간 예약 슬롯이 있습니다."
                );
            }
        }
    }

    private void addColumnIfMissing(Connection connection) throws SQLException {
        if (!columnExists(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table reservation_slots
                        add column business_date date null
                        """);
            }
        }
    }

    private void applyNotNull(Connection connection) throws SQLException {
        if (columnNullable(connection)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        alter table reservation_slots
                        modify column business_date date not null
                        """);
            }
        }
    }

    private void assertTargetSchema(Connection connection) throws SQLException {
        if (!columnExists(connection) || columnNullable(connection)) {
            throw new IllegalStateException(
                    "reservation_slots.business_date NOT NULL 컬럼이 없습니다."
            );
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     select count(*)
                       from reservation_slots
                      where business_date is null
                     """)) {
            if (!resultSet.next() || resultSet.getInt(1) > 0) {
                throw new IllegalStateException("영업 기준일이 없는 예약 슬롯이 있습니다.");
            }
        }
    }

    private boolean columnExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_slots'
                   and column_name = 'business_date'
                   and data_type = 'date'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private boolean columnNullable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select is_nullable
                  from information_schema.columns
                 where table_schema = database()
                   and table_name = 'reservation_slots'
                   and column_name = 'business_date'
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "reservation_slots.business_date 컬럼이 없습니다."
                    );
                }
                return "YES".equalsIgnoreCase(resultSet.getString(1));
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
        try (PreparedStatement statement = connection.prepareStatement("select get_lock(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "예약 슬롯 영업 기준일 마이그레이션 잠금을 획득하지 못했습니다."
                    );
                }
            }
        }
    }

    private void releaseLock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("예약 슬롯 영업 기준일 마이그레이션 잠금 해제에 실패했습니다.", exception);
        }
    }
}
