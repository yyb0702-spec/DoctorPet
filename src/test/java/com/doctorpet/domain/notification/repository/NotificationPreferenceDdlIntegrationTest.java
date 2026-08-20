package com.doctorpet.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.notification.channel.NotificationChannelType;
import com.doctorpet.domain.notification.entity.NotificationPreference;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — 알림 수신 설정 테이블의 스키마 계약을 실제 MySQL로 검증한다(고도화 3.9 스키마 선반영).
 *
 * <p>설정 변경 API는 이 범위가 아니므로 여기서 보는 것은 스키마다. ① (회원 · 유형 · 채널)당 1행을 DB가 강제하는지
 * — 이 제약이 없으면 나중에 설정 API가 upsert할 때 같은 조합이 둘로 갈려 어느 쪽이 이기는지 모호해진다.
 * ② 두 enum 컬럼이 native ENUM이 아니라 varchar인지 — ENUM이면 알림 유형·채널을 추가할 때마다 확장
 * 마이그레이션이 다시 필요해진다(이슈 #176에서 notifications에 대해 없앤 바로 그 부채를 새 테이블이 되살리지
 * 않게 한다). ③ 저장·조회 왕복.
 *
 * <p>새 테이블이라 전용 마이그레이션 러너가 없다 — {@code ddl-auto=update}가 테이블·UNIQUE를 함께 만든다.
 * 그 전제가 실제로 성립하는지도 이 테스트가 확인하는 대상이다.
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
class NotificationPreferenceDdlIntegrationTest {

    private static final String UNIQUE_NAME = "uk_notification_preferences_member_type_channel";

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberIds.forEach(id ->
                jdbcTemplate.update("delete from notification_preferences where member_id = ?", id));
    }

    @Test
    @DisplayName("(회원·유형·채널)당 1행을 UNIQUE 제약이 강제한다")
    void uniqueConstraint_rejectsDuplicateCombination() {
        Long memberId = nextMemberId();
        preferenceRepository.saveAndFlush(NotificationPreference.of(
                memberId, NotificationType.PAYMENT_RESULT, NotificationChannelType.EMAIL, false));

        assertThatThrownBy(() -> preferenceRepository.saveAndFlush(NotificationPreference.of(
                memberId, NotificationType.PAYMENT_RESULT, NotificationChannelType.EMAIL, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 회원·유형이라도 채널이 다르면 따로 저장된다")
    void differentChannel_isSeparateRow() {
        Long memberId = nextMemberId();
        preferenceRepository.saveAndFlush(NotificationPreference.of(
                memberId, NotificationType.PAYMENT_RESULT, NotificationChannelType.EMAIL, false));
        preferenceRepository.saveAndFlush(NotificationPreference.of(
                memberId, NotificationType.PAYMENT_RESULT, NotificationChannelType.REALTIME, true));

        assertThat(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                memberId, NotificationType.PAYMENT_RESULT, NotificationChannelType.EMAIL))
                .isPresent()
                .get()
                .extracting(NotificationPreference::isEnabled)
                .isEqualTo(false);
        assertThat(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                memberId, NotificationType.PAYMENT_RESULT, NotificationChannelType.REALTIME))
                .isPresent()
                .get()
                .extracting(NotificationPreference::isEnabled)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("설정이 없는 조합은 빈 Optional이다(호출부가 기본 수신으로 해석한다)")
    void missingCombination_isEmpty() {
        assertThat(preferenceRepository.findByMemberIdAndNotificationTypeAndChannel(
                nextMemberId(), NotificationType.RESERVATION_CONFIRMED, NotificationChannelType.EMAIL))
                .isEmpty();
    }

    @Test
    @DisplayName("UNIQUE 제약이 지정한 이름으로 실제 생성된다")
    void uniqueConstraint_existsWithGivenName() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'notification_preferences'
                   and index_name = ?
                """, Integer.class, UNIQUE_NAME);

        assertThat(count).isEqualTo(3); // member_id · notification_type · channel 3개 컬럼의 복합 UNIQUE
    }

    @Test
    @DisplayName("enum 컬럼이 native ENUM이 아니라 varchar로 생성된다")
    void enumColumns_areVarchar() {
        // ENUM이면 알림 유형·채널을 추가할 때마다 확장 마이그레이션이 필요해진다(이슈 #176) — 새 테이블이
        // 그 부채를 되살리지 않는지 엔티티 애너테이션(@JdbcTypeCode(SqlTypes.VARCHAR))의 효과로 확인한다.
        assertThat(columnType("notification_type")).isEqualTo("varchar(40)");
        assertThat(columnType("channel")).isEqualTo("varchar(20)");
    }

    private String columnType(String columnName) {
        return jdbcTemplate.queryForObject("""
                select column_type from information_schema.columns
                 where table_schema = database()
                   and table_name = 'notification_preferences'
                   and column_name = ?
                """, String.class, columnName);
    }

    private Long nextMemberId() {
        Long memberId = Math.abs(System.nanoTime());
        memberIds.add(memberId);
        return memberId;
    }
}
