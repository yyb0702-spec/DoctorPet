package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationEvent;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationEventRepository;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class HospitalNoShowIntegrationTest {

    @Autowired
    private HospitalReservationApplicationService hospitalReservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private ReservationEventRepository reservationEventRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long reservationId;
    private Long slotId;
    private Long staffMemberId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            jdbcTemplate.update(
                    "delete from reservation_events where reservation_id = ?",
                    reservationId
            );
            jdbcTemplate.update(
                    "delete from reservations where id = ?",
                    reservationId
            );
        }
        if (slotId != null) {
            jdbcTemplate.update(
                    "delete from reservation_slots where id = ?",
                    slotId
            );
        }
        if (staffMemberId != null) {
            jdbcTemplate.update(
                    "delete from members where id = ?",
                    staffMemberId
            );
        }
    }

    @Test
    @DisplayName("수동 노쇼 확정과 정정은 상태를 바꾸고 처리자·사유 이력을 순서대로 추가한다")
    void confirmAndRestore_appendsAuditHistory() {
        TestReservation data = saveConfirmedReservation();

        hospitalReservationService.confirmNoShow(
                data.staffMemberId(),
                data.reservationId(),
                "예약 시간 미방문"
        );

        Reservation noShow = reservationRepository.findById(data.reservationId())
                .orElseThrow();
        assertThat(noShow.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(noShow.getNoShowAt()).isNotNull();
        assertThat(reservationSlotRepository.findById(data.slotId()).orElseThrow()
                .getStatus()).isEqualTo(ReservationSlotStatus.RESERVED);

        hospitalReservationService.confirmNoShow(
                data.staffMemberId(),
                data.reservationId(),
                "중복 요청"
        );

        hospitalReservationService.restoreNoShow(
                data.staffMemberId(),
                data.reservationId(),
                "늦게 도착해 현장 접수"
        );

        Reservation restored = reservationRepository.findById(data.reservationId())
                .orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(ReservationStatus.CHECKED_IN);
        assertThat(restored.getNoShowAt()).isEqualTo(noShow.getNoShowAt());

        List<ReservationEvent> events = reservationEventRepository
                .findAllByReservation_IdOrderByOccurredAtAsc(data.reservationId());
        assertThat(events).hasSize(2);
        assertThat(events)
                .extracting(ReservationEvent::getEventType)
                .containsExactly(
                        ReservationEventType.MANUAL_NO_SHOW,
                        ReservationEventType.NO_SHOW_CORRECTED
                );
        assertThat(events)
                .extracting(ReservationEvent::getMemo)
                .containsExactly("예약 시간 미방문", "늦게 도착해 현장 접수");
        assertThat(events)
                .extracting(ReservationEvent::getProcessedBy)
                .containsOnly(data.staffMemberId());
    }

    @Test
    @DisplayName("자동·수동 노쇼가 동시에 실행되어도 수동 이력은 한 번 남고 최종 상태는 NO_SHOW다")
    void automaticAndManualRace_keepsManualDecisionIdempotently()
            throws InterruptedException {
        TestReservation data = saveConfirmedReservation();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Runnable automatic = () -> {
            int updated = jdbcTemplate.update("""
                    update reservations
                       set status = 'NO_SHOW', no_show_at = now(), updated_at = now()
                     where id = ? and status = 'CONFIRMED'
                    """, data.reservationId());
            if (updated == 1) {
                jdbcTemplate.update("""
                        insert ignore into reservation_events
                            (reservation_id, event_type, memo, processed_by, occurred_at)
                        values (?, 'AUTO_NO_SHOW', null, null, now())
                        """, data.reservationId());
            }
        };
        Runnable manual = () -> hospitalReservationService.confirmNoShow(
                data.staffMemberId(),
                data.reservationId(),
                "직원이 현장 확인"
        );

        for (Runnable task : List.of(automatic, manual)) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (Throwable throwable) {
                    errors.add(throwable);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(reservationRepository.findById(data.reservationId())
                .orElseThrow().getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservationEventRepository.countByReservation_IdAndEventType(
                data.reservationId(),
                ReservationEventType.MANUAL_NO_SHOW
        )).isEqualTo(1L);
        assertThat(reservationEventRepository.countByReservation_IdAndEventType(
                data.reservationId(),
                ReservationEventType.AUTO_NO_SHOW
        )).isBetween(0L, 1L);
        assertThat(reservationSlotRepository.findById(data.slotId()).orElseThrow()
                .getStatus()).isEqualTo(ReservationSlotStatus.RESERVED);
    }

    private TestReservation saveConfirmedReservation() {
        long hospitalId = System.nanoTime();
        long guardianMemberId = hospitalId + 1;
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID).withNano(0);

        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                now.minusMinutes(11),
                now.plusMinutes(19)
        );
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        Reservation reservation = Reservation.request(
                guardianMemberId,
                1L,
                hospitalId,
                slot.getId(),
                1L,
                "초코",
                "DOG",
                now.minusDays(1)
        );
        ReflectionTestUtils.setField(
                reservation,
                "status",
                ReservationStatus.CONFIRMED
        );
        ReflectionTestUtils.setField(reservation, "confirmedAt", now.minusDays(1));
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationId = reservation.getId();

        Member staff = Member.createGuardian(
                "no-show-" + System.nanoTime() + "@example.com",
                "encoded-password",
                "병원스태프"
        );
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        staff = memberRepository.saveAndFlush(staff);
        staffMemberId = staff.getId();

        return new TestReservation(
                staff.getId(),
                reservation.getId(),
                slot.getId()
        );
    }

    private record TestReservation(
            Long staffMemberId,
            Long reservationId,
            Long slotId
    ) {
    }
}
