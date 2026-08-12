package com.doctorpet.domain.hospital.partnership.runner;

import com.doctorpet.domain.hospital.partnership.service.PartnerHospitalSeedService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기존 제휴 병원에 누락된 최초 운영 스케줄을 한 번만 생성한다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "hospital.operating-schedule-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PartnerHospitalOperatingScheduleMigrationRunner
        implements ApplicationRunner {

    static final String MIGRATION_KEY =
            "partner_hospital_operating_schedule_initialization_v1";
    private static final String LOCK_NAME =
            "doctorpet:partner_hospital_operating_schedule_initialization";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final DataSource dataSource;
    private final PartnerHospitalSeedService partnerHospitalSeedService;
    private final Clock applicationClock;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            acquireLock(connection);
            try {
                migrate(connection);
            } finally {
                releaseLock(connection);
            }
        }
    }

    private void migrate(Connection connection) throws SQLException {
        if (migrationApplied(connection)) {
            return;
        }

        LocalDate effectiveDate = LocalDate.now(applicationClock);
        int initialized = partnerHospitalSeedService
                .initializeMissingOperatingSchedules(effectiveDate);
        recordMigration(connection);
        log.info(
                "제휴 병원 초기 운영 스케줄 마이그레이션 완료: "
                        + "effectiveDate={}, initialized={}",
                effectiveDate,
                initialized
        );
    }

    private boolean migrationApplied(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """)) {
            statement.setString(1, MIGRATION_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private void recordMigration(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                on duplicate key update migration_key = migration_key
                """)) {
            statement.setString(1, MIGRATION_KEY);
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
                            "제휴 병원 초기 운영 스케줄 마이그레이션 잠금을 "
                                    + "획득하지 못했습니다."
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
                    log.warn("제휴 병원 초기 운영 스케줄 마이그레이션 잠금이 "
                            + "해제되지 않았습니다.");
                }
            }
        } catch (SQLException exception) {
            log.warn(
                    "제휴 병원 초기 운영 스케줄 마이그레이션 잠금 해제에 실패했습니다.",
                    exception
            );
        }
    }
}
