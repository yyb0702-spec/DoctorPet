package com.doctorpet.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.notification.dto.response.NotificationResponse;
import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.domain.notification.repository.NotificationRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Level 3 — 수신자 인식 저장·조회·읽음의 병원 단위 공유와 회원↔병원 격리를 실제 MySQL·서비스 배선으로 검증한다
 * (고도화 3.10). 전체 컨텍스트라 MigrationRunner가 기동 시 member_id를 nullable로 완화해 병원 알림(member_id=NULL)
 * 저장이 성립한다 — 이 슬라이스가 성립한다는 사실 자체가 백필·완화 DDL이 적용됐다는 방증이다. 수신자 id는 테스트마다
 * 유일값을 써 공유 DB의 다른 데이터와 섞이지 않게 한다. 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class NotificationRecipientIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long memberId;
    private long hospitalId;
    private final List<Long> notificationIds = new ArrayList<>();

    @BeforeEach
    void uniqueRecipients() {
        long base = Math.abs(System.nanoTime());
        memberId = base;
        hospitalId = base + 1;
    }

    @AfterEach
    void cleanUp() {
        for (Long id : notificationIds) {
            jdbcTemplate.update("delete from notifications where id = ?", id);
        }
    }

    @Test
    @DisplayName("병원 단위 공유: 한 스태프가 병원 알림을 읽으면 그 병원의 해당 알림이 읽음 처리되고, 다른 스태프의 재읽음은 멱등이다")
    void hospitalRead_isSharedAcrossStaffAndIdempotent() {
        Long id = createHospitalNotification("새 예약 요청이 접수되었습니다.").getId();
        NotificationRecipient hospital = NotificationRecipient.hospital(hospitalId);
        assertThat(notificationService.getUnreadCount(hospital).unreadCount()).isEqualTo(1L);

        // 스태프 A가 읽는다 — 알림 1건당 공유이므로 개인 복제 없이 그 행의 read_at이 채워진다.
        notificationService.markAsRead(hospital, id);
        Notification afterFirst = notificationRepository.findById(id).orElseThrow();
        assertThat(afterFirst.getReadAt()).isNotNull();
        assertThat(notificationService.getUnreadCount(hospital).unreadCount()).isZero();

        // 스태프 B가 같은 알림을 다시 읽어도(공유 상태) 예외 없이 최초 시각이 유지된다(멱등).
        notificationService.markAsRead(hospital, id);
        assertThat(notificationRepository.findById(id).orElseThrow().getReadAt())
                .isEqualTo(afterFirst.getReadAt());

        // 병원 수신은 스태프별 개인 복제가 없다 — 여전히 알림 1건뿐이다.
        assertThat(notificationService.getMyNotifications(hospital, null, 0, 20).content()).hasSize(1);
    }

    @Test
    @DisplayName("격리: 회원과 병원 수신은 서로의 알림을 목록·읽음에서 접근할 수 없다")
    void memberAndHospital_areIsolated() {
        Long hospitalNotificationId = createHospitalNotification("새 예약 요청이 접수되었습니다.").getId();
        Long memberNotificationId = createMemberNotification("진료비 결제가 완료되었습니다.").getId();

        NotificationRecipient member = NotificationRecipient.member(memberId);
        NotificationRecipient hospital = NotificationRecipient.hospital(hospitalId);

        // 목록은 각자 자기 수신만 본다.
        List<NotificationResponse> memberList =
                notificationService.getMyNotifications(member, null, 0, 20).content();
        assertThat(memberList).hasSize(1);
        assertThat(memberList.get(0).id()).isEqualTo(memberNotificationId);

        List<NotificationResponse> hospitalList =
                notificationService.getMyNotifications(hospital, null, 0, 20).content();
        assertThat(hospitalList).hasSize(1);
        assertThat(hospitalList.get(0).id()).isEqualTo(hospitalNotificationId);

        // 회원이 병원 알림을, 병원이 회원 알림을 읽음 처리하려 하면 403(NOTIFICATION_ACCESS_DENIED).
        assertThatThrownBy(() -> notificationService.markAsRead(member, hospitalNotificationId))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);
        assertThatThrownBy(() -> notificationService.markAsRead(hospital, memberNotificationId))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getErrorCode())
                .isEqualTo(NotificationErrorCode.NOTIFICATION_ACCESS_DENIED);

        // 접근 거부 이후에도 두 알림 모두 미읽음으로 남는다.
        assertThat(notificationRepository.findById(hospitalNotificationId).orElseThrow().isRead()).isFalse();
        assertThat(notificationRepository.findById(memberNotificationId).orElseThrow().isRead()).isFalse();
    }

    @Test
    @DisplayName("모두 읽음: 병원 수신자의 미읽음만 전부 읽음 처리하고 회원 수신 알림은 불변이다")
    void markAllRead_isScopedToHospitalRecipient() {
        Long h1 = createHospitalNotification("예약 요청 1").getId();
        Long h2 = createHospitalNotification("예약 요청 2").getId();
        Long m1 = createMemberNotification("결제 완료").getId();

        int updated = notificationService.markAllRead(NotificationRecipient.hospital(hospitalId))
                .updatedCount();

        assertThat(updated).isEqualTo(2);
        assertThat(notificationRepository.findById(h1).orElseThrow().isRead()).isTrue();
        assertThat(notificationRepository.findById(h2).orElseThrow().isRead()).isTrue();
        // 회원 수신 알림은 그대로 미읽음이다.
        assertThat(notificationRepository.findById(m1).orElseThrow().isRead()).isFalse();
    }

    @Test
    @DisplayName("전체 삭제: 병원 수신자 알림만 하드 삭제하고 다른 병원·회원 알림은 불변, 두 번째 호출은 0건(멱등)")
    void deleteAll_isScopedToHospitalRecipient() {
        // 병원 A/B와 회원 알림을 함께 저장해, (HOSPITAL, A) 전체 삭제가 병원 A만 지우는 병원 단위 격리를 실 DB로 검증한다.
        long hospitalIdB = hospitalId + 1; // base+2 — 유일값이라 공유 DB의 다른 병원과 섞이지 않는다.
        createHospitalNotification("병원 A 예약 요청 1");
        createHospitalNotification("병원 A 예약 요청 2");
        Long hospitalBNotificationId = createHospitalNotificationForHospital(hospitalIdB, "병원 B 예약 요청").getId();
        Long memberNotificationId = createMemberNotification("결제 완료").getId();

        int deleted = notificationService.deleteAll(NotificationRecipient.hospital(hospitalId)).deletedCount();

        assertThat(deleted).isEqualTo(2);
        // 병원 A 알림은 모두 사라진다.
        assertThat(notificationService.getMyNotifications(NotificationRecipient.hospital(hospitalId), null, 0, 20)
                .content()).isEmpty();
        // 다른 병원(B)과 회원 수신 알림은 그대로 남는다.
        assertThat(notificationRepository.findById(hospitalBNotificationId)).isPresent();
        assertThat(notificationRepository.findById(memberNotificationId)).isPresent();

        // 두 번째 호출은 삭제할 게 없어 0건(멱등).
        assertThat(notificationService.deleteAll(NotificationRecipient.hospital(hospitalId)).deletedCount()).isZero();
    }

    private Notification createHospitalNotification(String content) {
        return createHospitalNotificationForHospital(hospitalId, content);
    }

    private Notification createHospitalNotificationForHospital(long targetHospitalId, String content) {
        Notification saved = notificationService.create(
                NotificationRecipientType.HOSPITAL,
                targetHospitalId,
                NotificationType.RESERVATION_CONFIRMED,
                content,
                NotificationResourceType.RESERVATION,
                77L
        );
        notificationIds.add(saved.getId());
        return saved;
    }

    private Notification createMemberNotification(String content) {
        Notification saved = notificationService.create(
                memberId,
                NotificationType.PAYMENT_RESULT,
                content,
                NotificationResourceType.PAYMENT,
                55L
        );
        notificationIds.add(saved.getId());
        return saved;
    }
}
