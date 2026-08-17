package com.doctorpet.domain.reservation.scheduler;

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

/** 여러 인스턴스에서 같은 대기열 제안 만료 배치를 중복 실행하지 않게 하는 MySQL named lock. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationWaitlistExpirationLock {

    private static final String LOCK_NAME = "doctorpet:reservation-waitlist-expiration";

    private final JdbcTemplate jdbcTemplate;

    public <T> Optional<T> executeIfAcquired(int waitSeconds, Supplier<T> action) {
        return jdbcTemplate.execute((ConnectionCallback<Optional<T>>) connection -> {
            if (!acquire(connection, waitSeconds)) {
                return Optional.empty();
            }
            try {
                return Optional.of(action.get());
            } finally {
                release(connection);
            }
        });
    }

    private boolean acquire(Connection connection, int waitSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select get_lock(?, ?)")) {
            statement.setString(1, LOCK_NAME);
            statement.setInt(2, waitSeconds);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private void release(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("select release_lock(?)")) {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery();
        } catch (SQLException exception) {
            log.warn("예약 대기열 승급 제안 만료 DB 잠금 해제 실패", exception);
        }
    }
}
