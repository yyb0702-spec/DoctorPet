package com.doctorpet.domain.hospital.scheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HospitalSlotGenerationLock {

    private static final String LOCK_NAME = "doctorpet:hospital-slot-generation";

    private final JdbcTemplate jdbcTemplate;

    public <T> Optional<T> executeIfAcquired(Supplier<T> action) {
        return jdbcTemplate.execute(
                (ConnectionCallback<Optional<T>>) connection -> {
                    if (!acquire(connection)) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of(action.get());
                    } finally {
                        release(connection);
                    }
                }
        );
    }

    private boolean acquire(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select get_lock(?, 0)"
        )) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private void release(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select release_lock(?)"
        )) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("병원 예약 슬롯 생성 DB 잠금 해제 실패", exception);
        }
    }
}
