package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.doctorpet.domain.reservation.repository.ReservationNoShowTarget;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationNoShowLock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "ai.gateway=fake",
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
    private ReservationNoShowProcessor noShowProcessor;

    @Autowired
    private ReservationNoShowLock noShowLock;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long reservationId;
    private Long slotId;
    private Long staffMemberId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            jdbcTemplate.update(
                    "delete from notifications where resource_type = 'RESERVATION' and resource_id = ?",
                    reservationId
            );
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
    @DisplayName("예약 시각 +10분까지는 유예하고 이후 CONFIRMED 예약만 자동 노쇼 대상이다")
    void autoNoShowBoundary_selectsOnlyEligibleConfirmedReservation() {
        LocalDateTime cutoff = LocalDateTime.of(2030, 1, 1, 10, 0);
        TestReservation data = saveConfirmedReservation(cutoff);

        assertThat(reservationRepository.findAutoNoShowTargets(
                ReservationStatus.CONFIRMED,
                cutoff,
                PageRequest.of(0, 100)
        )).extracting(ReservationNoShowTarget::getReservationId)
                .doesNotContain(data.reservationId());

        ReservationSlot slot = reservationSlotRepository.findById(data.slotId()).orElseThrow();
        ReflectionTestUtils.setField(slot, "startAt", cutoff.minusMinutes(1));
        ReflectionTestUtils.setField(slot, "endAt", cutoff.plusMinutes(29));
        reservationSlotRepository.saveAndFlush(slot);
        assertThat(reservationRepository.findAutoNoShowTargets(
                ReservationStatus.CONFIRMED,
                cutoff,
                PageRequest.of(0, 100)
        )).extracting(ReservationNoShowTarget::getReservationId)
                .contains(data.reservationId());
    }

    @Test
    @DisplayName("자동 노쇼 처리는 상태·SYSTEM 이력·알림을 한 번만 생성한다")
    void autoNoShow_isAtomicAndIdempotent() {
        TestReservation data = saveConfirmedReservation();
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        ReservationNoShowProcessor.Result first = noShowProcessor.process(data.reservationId(), now);
        ReservationNoShowProcessor.Result second = noShowProcessor.process(data.reservationId(), now);

        assertThat(first).isEqualTo(ReservationNoShowProcessor.Result.PROCESSED);
        assertThat(second).isEqualTo(ReservationNoShowProcessor.Result.SKIPPED);
        assertThat(reservationRepository.findById(data.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservationEventRepository.countByReservation_IdAndEventType(
                data.reservationId(), ReservationEventType.AUTO_NO_SHOW)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from reservation_events where reservation_id = ? and event_type = 'AUTO_NO_SHOW' and processed_by is null",
                Long.class, data.reservationId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select memo from reservation_events where reservation_id = ? and event_type = 'AUTO_NO_SHOW'",
                String.class, data.reservationId()))
                .isEqualTo("예약 시각 이후 체크인 미확인으로 자동 판정");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from notifications where resource_type = 'RESERVATION' and resource_id = ? and type = 'NO_SHOW'",
                Long.class, data.reservationId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 인스턴스가 자동 노쇼 잠금을 보유하면 두 번째 실행은 스킵한다")
    void autoNoShowLock_allowsOnlyOneInstance() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> noShowLock.executeIfAcquired(0, () -> {
                acquired.countDown();
                await(release);
                return true;
            }));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(noShowLock.executeIfAcquired(0, () -> true)).isEmpty();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(true);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
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

        Runnable automatic = () -> noShowProcessor.process(
                data.reservationId(),
                LocalDateTime.now(SEOUL_ZONE_ID)
        );
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

    @Test
    @DisplayName("체크인과 자동 노쇼가 경합하면 정확히 하나의 상태 전이만 성립한다")
    void checkInAndAutomaticRace_onlyOneTransitionSucceeds() throws InterruptedException {
        TestReservation data = saveConfirmedReservation();
        Reservation reservation = reservationRepository.findById(data.reservationId()).orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        Runnable checkIn = () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            await(start);
            reservationRepository.checkInIfConfirmed(
                    data.reservationId(), reservation.getHospitalId(),
                    ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN,
                    LocalDateTime.now(SEOUL_ZONE_ID)
            );
        });
        Runnable automatic = () -> {
            await(start);
            noShowProcessor.process(data.reservationId(), LocalDateTime.now(SEOUL_ZONE_ID));
        };

        for (Runnable task : List.of(checkIn, automatic)) {
            executor.submit(() -> {
                try { task.run(); } catch (Throwable throwable) { errors.add(throwable); }
                finally { done.countDown(); }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(reservationRepository.findById(data.reservationId()).orElseThrow().getStatus())
                .isIn(ReservationStatus.CHECKED_IN, ReservationStatus.NO_SHOW);
        assertThat(reservationEventRepository.countByReservation_IdAndEventType(
                data.reservationId(), ReservationEventType.AUTO_NO_SHOW)).isBetween(0L, 1L);
    }

    @Test
    @DisplayName("수동 트랜잭션의 스냅샷 이후 자동 노쇼가 커밋되어도 잠금 조회는 최신 상태를 읽는다")
    void automaticCommitAfterManualSnapshot_forUpdateReadsLatestStatus()
            throws InterruptedException {
        TestReservation data = saveConfirmedReservation();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch snapshotFixed = new CountDownLatch(1);
        CountDownLatch automaticCommitted = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger manualUpdated = new AtomicInteger(-1);
        AtomicReference<ReservationStatus> lockedStatus = new AtomicReference<>();

        executor.submit(() -> {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    Reservation snapshot = reservationRepository
                            .findById(data.reservationId())
                            .orElseThrow();
                    assertThat(snapshot.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                    snapshotFixed.countDown();
                    await(automaticCommitted);

                    LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
                    manualUpdated.set(reservationRepository.markNoShowIfConfirmed(
                            data.reservationId(),
                            snapshot.getHospitalId(),
                            ReservationStatus.CONFIRMED,
                            ReservationStatus.NO_SHOW,
                            now,
                            now
                    ));
                    lockedStatus.set(reservationRepository
                            .findByIdAndHospitalIdForUpdate(
                                    data.reservationId(),
                                    snapshot.getHospitalId()
                            )
                            .orElseThrow()
                            .getStatus());
                });
            } catch (Throwable throwable) {
                errors.add(throwable);
            } finally {
                done.countDown();
            }
        });

        executor.submit(() -> {
            try {
                await(snapshotFixed);
                int updated = jdbcTemplate.update("""
                        update reservations
                           set status = 'NO_SHOW', no_show_at = now(), updated_at = now()
                         where id = ? and status = 'CONFIRMED'
                        """, data.reservationId());
                assertThat(updated).isEqualTo(1);
            } catch (Throwable throwable) {
                errors.add(throwable);
            } finally {
                automaticCommitted.countDown();
                done.countDown();
            }
        });

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(manualUpdated).hasValue(0);
        assertThat(lockedStatus).hasValue(ReservationStatus.NO_SHOW);
    }

    @Test
    @DisplayName("노쇼 조건부 UPDATE는 다른 병원 ID로 예약 상태를 변경하지 않는다")
    void markNoShowWithOtherHospitalId_updatesNothing() {
        TestReservation data = saveConfirmedReservation();

        Integer updated = new TransactionTemplate(transactionManager).execute(status -> {
            LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
            return reservationRepository.markNoShowIfConfirmed(
                    data.reservationId(),
                    Long.MIN_VALUE,
                    ReservationStatus.CONFIRMED,
                    ReservationStatus.NO_SHOW,
                    now,
                    now
            );
        });

        assertThat(updated).isZero();
        assertThat(reservationRepository.findById(data.reservationId())
                .orElseThrow()
                .getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("감사 이력 저장은 중복 키가 아닌 외래 키 오류를 숨기지 않는다")
    void appendHistoryWithUnknownReservation_failsTransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> reservationEventRepository.appendIfAbsent(
                        Long.MAX_VALUE,
                        ReservationEventType.MANUAL_NO_SHOW.name(),
                        "존재하지 않는 예약",
                        null,
                        LocalDateTime.now(SEOUL_ZONE_ID)
                )))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단되었습니다.", exception);
        }
    }

    private TestReservation saveConfirmedReservation() {
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID).withNano(0);
        return saveConfirmedReservation(now.minusMinutes(11));
    }

    private TestReservation saveConfirmedReservation(LocalDateTime slotStartAt) {
        long hospitalId = System.nanoTime();
        long guardianMemberId = hospitalId + 1;
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID).withNano(0);

        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                slotStartAt,
                slotStartAt.plusMinutes(30)
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
