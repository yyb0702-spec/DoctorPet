package com.doctorpet.domain.reservation.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "reservation.event-unique-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ReservationEventUniqueMigrationRunner implements ApplicationRunner {

    static final String MIGRATION_KEY = "reservation_event_unique_v1";
    private static final String UNIQUE_INDEX_NAME = "uk_reservation_event_type";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        if (migrationApplied()) {
            assertUniqueConstraintExists();
            return;
        }

        int deletedCount = jdbcTemplate.update("""
                delete duplicate_event
                  from reservation_events duplicate_event
                  join reservation_events original_event
                    on duplicate_event.reservation_id = original_event.reservation_id
                   and duplicate_event.event_type = original_event.event_type
                   and duplicate_event.id > original_event.id
                """);

        if (!uniqueConstraintExists()) {
            jdbcTemplate.execute("""
                    alter table reservation_events
                    add constraint uk_reservation_event_type
                    unique (reservation_id, event_type)
                    """);
        }

        assertUniqueConstraintExists();
        jdbcTemplate.update("""
                insert into schema_migrations (migration_key, applied_at)
                values (?, now())
                on duplicate key update migration_key = migration_key
                """, MIGRATION_KEY);

        log.info(
                "reservation_events UNIQUE 마이그레이션 완료: 중복 이력 {}건을 정리하고 {} 제약을 확인했습니다.",
                deletedCount,
                UNIQUE_INDEX_NAME
        );
    }

    private boolean migrationApplied() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from schema_migrations
                 where migration_key = ?
                """, Integer.class, MIGRATION_KEY);
        return count != null && count > 0;
    }

    private void assertUniqueConstraintExists() {
        if (!uniqueConstraintExists()) {
            throw new IllegalStateException(
                    "reservation_events의 (reservation_id, event_type) UNIQUE 제약이 없습니다."
            );
        }
    }

    private boolean uniqueConstraintExists() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from (
                        select index_name
                          from information_schema.statistics
                         where table_schema = database()
                           and table_name = 'reservation_events'
                           and non_unique = 0
                         group by index_name
                        having group_concat(column_name order by seq_in_index)
                               = 'reservation_id,event_type'
                       ) unique_indexes
                """, Integer.class);
        return count != null && count > 0;
    }
}
