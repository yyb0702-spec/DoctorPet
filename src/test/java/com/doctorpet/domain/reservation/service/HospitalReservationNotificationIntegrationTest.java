package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 3 — 승인·수동 거절 전이에서 보호자 알림이 실제 `notifications` 테이블에 저장되는지, 그리고 알림 저장이
 * 상태 전이 트랜잭션에 참여해 함께 롤백되는지 실제 MySQL로 검증한다(#88, PR #107 리뷰 P2).
 *
 * <p>승인 타임아웃 자동 거절·노쇼 경로는 각각 {@code ReservationApprovalTimeoutIntegrationTest},
 * {@code HospitalNoShowIntegrationTest}가 이미 저장을 검증한다.
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
class HospitalReservationNotificationIntegrationTest {

    @Autowired
    private HospitalReservationApplicationService hospitalReservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 기본은 실제 저장 동작을 그대로 쓰고, 롤백 검증 테스트에서만 예외를 던지도록 스텁한다.
    @MockitoSpyBean
    private NotificationService notificationService;

    private Long reservationId;
    private Long slotId;
    private Long staffMemberId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            jdbcTemplate.update(
                    "delete from notifications where resource_type = 'RESERVATION' and resource_id = ?",
                    reservationId);
            jdbcTemplate.update("delete from reservations where id = ?", reservationId);
        }
        if (slotId != null) {
            jdbcTemplate.update("delete from reservation_slots where id = ?", slotId);
        }
        if (staffMemberId != null) {
            jdbcTemplate.update("delete from members where id = ?", staffMemberId);
        }
    }

    @Test
    @DisplayName("승인 성공 시 RESERVATION_CONFIRMED 알림이 예약에 연결돼 1건 저장된다")
    void approve_persistsConfirmedNotification() {
        TestReservation data = saveRequestedReservation();

        hospitalReservationService.approve(data.staffMemberId(), data.reservationId());

        assertThat(reservationStatus(data.reservationId())).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(notificationCount(data.reservationId(), NotificationType.RESERVATION_CONFIRMED))
                .isEqualTo(1);
        assertThat(notificationRecipient(data.reservationId())).isEqualTo(data.guardianMemberId());
    }

    @Test
    @DisplayName("수동 거절 성공 시 RESERVATION_REJECTED 알림이 1건 저장되고 슬롯이 반환된다")
    void reject_persistsRejectedNotification() {
        TestReservation data = saveRequestedReservation();

        hospitalReservationService.reject(
                data.staffMemberId(), data.reservationId(), ReservationRejectReason.STAFF_SHORTAGE);

        assertThat(reservationStatus(data.reservationId())).isEqualTo(ReservationStatus.REJECTED);
        assertThat(slotStatus(data.slotId())).isEqualTo(ReservationSlotStatus.OPEN);
        assertThat(notificationCount(data.reservationId(), NotificationType.RESERVATION_REJECTED))
                .isEqualTo(1);
        assertThat(notificationRecipient(data.reservationId())).isEqualTo(data.guardianMemberId());
        assertThat(notificationContent(data.reservationId()))
                .isEqualTo("병원이 예약 요청을 거절했습니다. 사유: 직원 부족");
    }

    @Test
    @DisplayName("승인 중 알림 저장이 실패하면 예약 상태 변경도 함께 롤백된다")
    void notificationFailureOnApprove_rollsBackTransition() {
        TestReservation data = saveRequestedReservation();
        doThrow(new IllegalStateException("알림 저장 실패 시뮬레이션"))
                .when(notificationService)
                .create(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> hospitalReservationService.approve(
                data.staffMemberId(), data.reservationId()))
                .isInstanceOf(IllegalStateException.class);

        // 알림 저장은 상태 전이와 같은 트랜잭션이므로 전이도 남지 않아야 한다.
        // (승인 경로는 슬롯을 바꾸지 않으므로 슬롯 롤백 검증은 아래 거절 케이스가 담당한다.)
        assertThat(reservationStatus(data.reservationId())).isEqualTo(ReservationStatus.REQUESTED);
        assertThat(totalNotificationCount(data.reservationId())).isZero();
    }

    @Test
    @DisplayName("수동 거절 중 알림 저장이 실패하면 예약 상태와 슬롯 반환(slot.open())까지 함께 롤백된다")
    void notificationFailureOnReject_rollsBackTransitionAndSlot() {
        // 거절 경로는 조건부 UPDATE 외에 slot.open()(JPA dirty checking)까지 수행하므로,
        // 알림 저장 실패 시 슬롯 반환이 실제로 롤백되는지는 이 경로에서만 검증할 수 있다(PR #107 리뷰 P2).
        TestReservation data = saveRequestedReservation();
        doThrow(new IllegalStateException("알림 저장 실패 시뮬레이션"))
                .when(notificationService)
                .create(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> hospitalReservationService.reject(
                data.staffMemberId(), data.reservationId(), ReservationRejectReason.STAFF_SHORTAGE))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reservationStatus(data.reservationId())).isEqualTo(ReservationStatus.REQUESTED);
        // slot.open()이 커밋되지 않아 여전히 RESERVED여야 한다.
        assertThat(slotStatus(data.slotId())).isEqualTo(ReservationSlotStatus.RESERVED);
        assertThat(totalNotificationCount(data.reservationId())).isZero();
    }

    private TestReservation saveRequestedReservation() {
        long hospitalId = System.nanoTime();
        long guardianMemberId = hospitalId + 1;
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        LocalDateTime startAt = now.plusDays(2).withNano(0);

        ReservationSlot slot = ReservationSlot.create(hospitalId, startAt, startAt.plusMinutes(30));
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        Reservation reservation = Reservation.request(
                guardianMemberId, 1L, hospitalId, slot.getId(), 1L, "초코", "DOG", now);
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationId = reservation.getId();

        Member staff = Member.createGuardian(
                "hospital-notification-" + System.nanoTime() + "@example.com",
                "encoded-password",
                "병원스태프");
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        staff = memberRepository.saveAndFlush(staff);
        staffMemberId = staff.getId();

        return new TestReservation(
                guardianMemberId, staff.getId(), reservation.getId(), slot.getId());
    }

    private ReservationStatus reservationStatus(Long id) {
        return jdbcTemplate.queryForObject(
                "select status from reservations where id = ?",
                (rs, rowNum) -> ReservationStatus.valueOf(rs.getString(1)),
                id);
    }

    private ReservationSlotStatus slotStatus(Long id) {
        return jdbcTemplate.queryForObject(
                "select status from reservation_slots where id = ?",
                (rs, rowNum) -> ReservationSlotStatus.valueOf(rs.getString(1)),
                id);
    }

    private long notificationCount(Long reservationId, NotificationType type) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications "
                        + "where resource_type = 'RESERVATION' and resource_id = ? and type = ?",
                Long.class,
                reservationId,
                type.name());
    }

    private long totalNotificationCount(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications "
                        + "where resource_type = 'RESERVATION' and resource_id = ?",
                Long.class,
                reservationId);
    }

    private Long notificationRecipient(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "select member_id from notifications "
                        + "where resource_type = 'RESERVATION' and resource_id = ?",
                Long.class,
                reservationId);
    }

    private String notificationContent(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "select content from notifications "
                        + "where resource_type = 'RESERVATION' and resource_id = ?",
                String.class,
                reservationId);
    }

    private record TestReservation(
            Long guardianMemberId,
            Long staffMemberId,
            Long reservationId,
            Long slotId
    ) {
    }
}
