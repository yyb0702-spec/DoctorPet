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
 * `ddl-auto=update`는 기존 ENUM 정의를 넓혀주지 않는다. 그래서 ENUM이던 동안에는 enum에 값만 추가하고 확장
 * 마이그레이션을 빼먹으면 컴파일·단위 테스트는 모두 통과하는데 **운영 DB에서 그 값의 INSERT만 실패**했다(MySQL 1265).
 *
 * <p>이슈 #176에서 두 컬럼을 VARCHAR로 전환하고 엔티티에 `@JdbcTypeCode(SqlTypes.VARCHAR)`로 못박아 그 원인을
 * 없앴으므로, 이 테스트의 계약도 두 가지로 바뀐다.
 * <ul>
 *   <li>**컬럼이 ENUM이 아니어야 한다** — 엔티티의 VARCHAR 고정이 사라지면 신규 DB에서 Hibernate가 다시 native
 *       ENUM을 만들고, 값 추가마다 마이그레이션이 필요한 상태로 조용히 되돌아간다. 그 회귀를 여기서 깨뜨린다.</li>
 *   <li>**varchar 길이가 가장 긴 상수를 담아야 한다** — 길이가 부족하면 여전히 그 값의 저장이 실패한다.</li>
 * </ul>
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
        // ENUM이던 동안 이 컬럼에는 확장 러너가 아예 없어, 값이 늘면 기존 DB에서 바로 깨졌다(#166 자가검토 발견).
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

        // ENUM으로 되돌아가면(엔티티의 @JdbcTypeCode(SqlTypes.VARCHAR) 유실) 값 추가마다 마이그레이션이 다시
        // 필요해진다 — 이슈 #176이 없앤 원인이므로 여기서 회귀를 잡는다.
        Matcher matcher = VARCHAR_LENGTH.matcher(columnType);
        assertThat(matcher.matches())
                .as("notifications.%s은 varchar여야 한다 — enum으로 되돌아가면 %s에 값을 추가할 때마다 확장 "
                        + "마이그레이션이 다시 필요해진다(이슈 #176, 엔티티의 @JdbcTypeCode(SqlTypes.VARCHAR) 확인). "
                        + "현재: %s", columnName, columnName, columnType)
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

}
