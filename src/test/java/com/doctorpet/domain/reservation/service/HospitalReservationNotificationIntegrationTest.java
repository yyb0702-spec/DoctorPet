package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import java.time.LocalDateTime;
import java.time.LocalDate;
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
    private ReservationWaitlistRepository reservationWaitlistRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 기본은 실제 저장 동작을 그대로 쓰고, 롤백 검증 테스트에서만 예외를 던지도록 스텁한다.
    @MockitoSpyBean
    private NotificationService notificationService;

    private Long reservationId;
    private Long slotId;
    private Long offeredOnlySlotId;
    private Long staffMemberId;
    private Long hospitalId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            jdbcTemplate.update(
                    "delete from notifications where resource_type = 'RESERVATION' and resource_id = ?",
                    reservationId);
            jdbcTemplate.update("delete from reservation_events where reservation_id = ?", reservationId);
            jdbcTemplate.update("delete from reservations where id = ?", reservationId);
        }
        if (slotId != null) {
            jdbcTemplate.update("delete from reservation_waitlists where slot_id = ?", slotId);
            jdbcTemplate.update("delete from reservation_slots where id = ?", slotId);
        }
        if (offeredOnlySlotId != null) {
            jdbcTemplate.update("delete from reservation_waitlists where slot_id = ?", offeredOnlySlotId);
            jdbcTemplate.update("delete from reservation_slots where id = ?", offeredOnlySlotId);
        }
        if (staffMemberId != null) {
            jdbcTemplate.update("delete from members where id = ?", staffMemberId);
        }
        if (hospitalId != null) {
            jdbcTemplate.update("delete from hospitals where id = ?", hospitalId);
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
    @DisplayName("병원 확정 예약 취소 시 상태·사유·알림을 실제 DB에 저장한다")
    void hospitalCancel_persistsStatusReasonAndNotification() {
        TestReservation data = saveRequestedReservation();
        jdbcTemplate.update(
                "update reservations set status = 'CONFIRMED' where id = ?",
                data.reservationId());

        hospitalReservationService.cancelConfirmedByHospital(
                data.staffMemberId(),
                data.reservationId(),
                "응급수술로 진료 불가"
        );

        assertThat(reservationStatus(data.reservationId()))
                .isEqualTo(ReservationStatus.HOSPITAL_CANCELED);
        assertThat(slotStatus(data.slotId())).isEqualTo(ReservationSlotStatus.OPEN);
        assertThat(jdbcTemplate.queryForObject(
                "select hospital_cancel_reason from reservations where id = ?",
                String.class,
                data.reservationId()))
                .isEqualTo("응급수술로 진료 불가");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from reservation_events where reservation_id = ? and event_type = 'HOSPITAL_CANCELED'",
                Integer.class,
                data.reservationId()))
                .isEqualTo(1);
        assertThat(notificationCount(
                data.reservationId(),
                NotificationType.RESERVATION_HOSPITAL_CANCELED
        )).isEqualTo(1);
        assertThat(notificationContent(data.reservationId()))
                .isEqualTo("병원이 확정된 예약을 취소했습니다. 사유: 응급수술로 진료 불가");
    }

    @Test
    @DisplayName("병원 취소 알림 저장 실패 시 예약 상태와 취소 사유를 함께 롤백한다")
    void hospitalCancel_notificationFailure_rollsBackTransition() {
        TestReservation data = saveRequestedReservation();
        jdbcTemplate.update(
                "update reservations set status = 'CONFIRMED' where id = ?",
                data.reservationId());
        doThrow(new IllegalStateException("알림 저장 실패 시뮬레이션"))
                .when(notificationService)
                .create(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> hospitalReservationService.cancelConfirmedByHospital(
                data.staffMemberId(),
                data.reservationId(),
                "병원 사정"
        )).isInstanceOf(IllegalStateException.class);

        assertThat(reservationStatus(data.reservationId()))
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject(
                "select hospital_cancel_reason from reservations where id = ?",
                String.class,
                data.reservationId()))
                .isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from reservation_events where reservation_id = ? and event_type = 'HOSPITAL_CANCELED'",
                Integer.class,
                data.reservationId()))
                .isZero();
        assertThat(slotStatus(data.slotId())).isEqualTo(ReservationSlotStatus.RESERVED);
        assertThat(totalNotificationCount(data.reservationId())).isZero();
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

    @Test
    @DisplayName("휴·폐업 자동 취소는 시스템 이력과 보호자 알림을 저장하고 슬롯을 반환한다")
    void businessStatusChange_persistsSystemCancellationAndNotification() {
        TestReservation data = saveRequestedReservation();
        jdbcTemplate.update(
                "update reservations set status = 'CONFIRMED' where id = ?",
                data.reservationId()
        );
        ReservationWaitlist waiting = reservationWaitlistRepository.saveAndFlush(
                ReservationWaitlist.waiting(data.guardianMemberId() + 100, data.slotId())
        );

        int canceledCount = hospitalReservationService
                .cancelConfirmedByBusinessStatusChange(
                        data.hospitalId(),
                        "공공데이터에서 병원 폐업이 확인되었습니다."
                );

        assertThat(canceledCount).isEqualTo(1);
        assertThat(reservationStatus(data.reservationId()))
                .isEqualTo(ReservationStatus.HOSPITAL_CANCELED);
        assertThat(slotStatus(data.slotId())).isEqualTo(ReservationSlotStatus.OPEN);
        assertThat(reservationWaitlistRepository.findById(waiting.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationWaitlistStatus.CANCELED);
        assertThat(jdbcTemplate.queryForObject(
                "select processed_by from reservation_events "
                        + "where reservation_id = ? and event_type = 'HOSPITAL_CANCELED'",
                Long.class,
                data.reservationId()
        )).isNull();
        assertThat(notificationCount(
                data.reservationId(),
                NotificationType.RESERVATION_HOSPITAL_CANCELED
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("휴·폐업 자동 취소는 CONFIRMED 예약 없이 OFFERED 대기열만 있는 슬롯도 종료한다")
    void businessStatusChange_cancelsOfferedWaitlistWithoutConfirmedReservation() {
        TestReservation data = saveRequestedReservation();
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        LocalDateTime offeredOnlyStartAt = now.plusDays(2).withNano(0).plusMinutes(30);
        ReservationSlot offeredOnlySlot = ReservationSlot.create(
                data.hospitalId(),
                offeredOnlyStartAt,
                offeredOnlyStartAt.plusMinutes(30)
        );
        offeredOnlySlot.reserve();
        offeredOnlySlot = reservationSlotRepository.saveAndFlush(offeredOnlySlot);
        offeredOnlySlotId = offeredOnlySlot.getId();

        ReservationWaitlist offered = ReservationWaitlist.waiting(
                data.guardianMemberId() + 200,
                offeredOnlySlotId
        );
        offered.offer(now.minusMinutes(1), now.plusMinutes(9));
        offered = reservationWaitlistRepository.saveAndFlush(offered);

        int canceledCount = hospitalReservationService.cancelConfirmedByBusinessStatusChange(
                data.hospitalId(),
                "공공데이터에서 병원 폐업이 확인되었습니다."
        );

        assertThat(canceledCount).isZero();
        assertThat(reservationWaitlistRepository.findById(offered.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationWaitlistStatus.CANCELED);
        assertThat(slotStatus(offeredOnlySlotId)).isEqualTo(ReservationSlotStatus.OPEN);
    }

    private TestReservation saveRequestedReservation() {
        Hospital hospital = Hospital.createFromPublicData(
                "notification-" + System.nanoTime(),
                "test-local-gov",
                "알림 테스트 병원",
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.now(),
                BusinessStatus.OPEN,
                null,
                null,
                LocalDateTime.now()
        );
        hospital.markAsPartner();
        hospital = hospitalRepository.saveAndFlush(hospital);
        hospitalId = hospital.getId();
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
                guardianMemberId,
                staff.getId(),
                hospitalId,
                reservation.getId(),
                slot.getId()
        );
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
            Long hospitalId,
            Long reservationId,
            Long slotId
    ) {
    }
}
