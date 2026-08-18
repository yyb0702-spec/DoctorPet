package com.doctorpet.domain.notification.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — 애플리케이션 enum의 **모든 값이 실제로 저장 가능한 상태**인지 실제 MySQL 스키마로 검증한다(#166 자가검토).
 *
 * <p>왜 필요한가: Hibernate는 `@Enumerated(EnumType.STRING)` 자바 enum을 MySQL native ENUM 컬럼으로 만들고,
 * `ddl-auto=update`는 기존 ENUM 정의를 넓혀주지 않는다. 그래서 enum에 값만 추가하고 확장 마이그레이션을 빼먹으면
 * 컴파일·단위 테스트는 모두 통과하는데 **운영 DB에서 그 값의 INSERT만 실패**한다(MySQL 1265). 게다가 기존 ENUM
 * 마이그레이션 러너들은 목표 목록을 하드코딩한 뒤 컬럼 정의를 통째로 교체하므로, 새 러너가 상위집합·실행 순서
 * 관례를 지키지 않으면 남의 값이 목록에서 조용히 빠질 수도 있다. 그 관례를 사람이 기억하는 대신 이 테스트가 깨지게 한다.
 *
 * <p>ENUM이 아닌 VARCHAR 컬럼에도 의미 있는 검사를 남긴다 — 길이가 가장 긴 상수를 담지 못하면 역시 저장이 실패하므로
 * `varchar(n)`의 n을 검사한다. 현재 두 컬럼은 여유가 거의 없다(`resource_type`은 `RESERVATION_WAITLIST` 20자에
 * `length = 20`으로 정확히 한계, `type`은 `RESERVATION_HOSPITAL_CANCELED` 29자에 `length = 30`).
 *
 * <p>수명: `notifications.type`·`resource_type`을 VARCHAR로 전환하면 ENUM 분기는 더 이상 쓰이지 않고 길이 검사만
 * 남는다. 전환 이슈에서 이 테스트의 ENUM 분기를 함께 정리한다.
 *
 * <p>전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
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
class NotificationEnumColumnSchemaIntegrationTest {

    private static final Pattern ENUM_VALUE = Pattern.compile("'([^']*)'");
    private static final Pattern VARCHAR_LENGTH = Pattern.compile("^varchar\\((\\d+)\\)$");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("NotificationType의 모든 값이 notifications.type에 저장 가능하다")
    void notificationType_everyValueIsPersistable() {
        assertEveryValuePersistable(
                "type",
                Arrays.stream(NotificationType.values()).map(Enum::name).toList()
        );
    }

    @Test
    @DisplayName("NotificationResourceType의 모든 값이 notifications.resource_type에 저장 가능하다")
    void notificationResourceType_everyValueIsPersistable() {
        // 이 컬럼에는 확장 마이그레이션 러너가 아예 없다 — 값이 늘면 기존 DB에서 바로 깨진다(#166 자가검토 발견).
        assertEveryValuePersistable(
                "resource_type",
                Arrays.stream(NotificationResourceType.values()).map(Enum::name).toList()
        );
    }

    private void assertEveryValuePersistable(String columnName, List<String> values) {
        String columnType = columnType(columnName);
        assertThat(columnType)
                .as("notifications.%s 컬럼이 존재해야 한다", columnName)
                .isNotNull();

        if (columnType.startsWith("enum(")) {
            List<String> declared = declaredEnumValues(columnType);
            assertThat(declared)
                    .as("notifications.%s ENUM 정의에 빠진 %s 값이 있으면, 그 값의 알림은 기존 DB에서 저장에 실패한다 "
                            + "— enum에 값을 추가했다면 확장 마이그레이션 러너도 함께 넣어야 한다 (현재 정의: %s)",
                            columnName, columnName, columnType)
                    .containsAll(values);
            return;
        }

        // VARCHAR로 전환된 뒤에도 남는 검사 — 가장 긴 상수를 담지 못하면 역시 저장이 실패한다.
        Matcher matcher = VARCHAR_LENGTH.matcher(columnType);
        assertThat(matcher.matches())
                .as("notifications.%s은 enum 또는 varchar여야 한다 (현재: %s)", columnName, columnType)
                .isTrue();
        int length = Integer.parseInt(matcher.group(1));
        String longest = values.stream().max(java.util.Comparator.comparingInt(String::length)).orElseThrow();
        assertThat(length)
                .as("notifications.%s varchar 길이가 가장 긴 상수 %s(%d자)를 담지 못한다",
                        columnName, longest, longest.length())
                .isGreaterThanOrEqualTo(longest.length());
    }

    private String columnType(String columnName) {
        List<String> found = jdbcTemplate.queryForList("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notifications'
                   and column_name = ?
                """, String.class, columnName);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<String> declaredEnumValues(String columnType) {
        Matcher matcher = ENUM_VALUE.matcher(columnType);
        List<String> values = new java.util.ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
