package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationEventRepository;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.scheduler.ReservationApprovalTimeoutLock;
import com.doctorpet.domain.reservation.scheduler.ReservationApprovalTimeoutSummary;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Level 3 — 실제 MySQL에서 승인 타임아웃의 트랜잭션·경합·멱등성을 검증한다. */
@SpringBootTest(properties = {
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "reservation.approval-timeout.initial-delay-ms=3600000"
})
class ReservationApprovalTimeoutIntegrationTest {

    @Autowired
    private ReservationApprovalTimeoutProcessor processor;

    @Autowired
    private ReservationApprovalTimeoutBatchService batchService;

    @Autowired
    private ReservationApprovalTimeoutLock timeoutLock;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @Autowired
    private ReservationEventRepository eventRepository;

    // 경합 테스트의 "수동 거절"을 운영과 같은 서비스 경로로 실행하기 위해 주입한다(PR #107 리뷰 P2).
    @Autowired
    private HospitalReservationApplicationService hospitalReservationService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private final List<Long> reservationIds = new ArrayList<>();
    private final List<Long> slotIds = new ArrayList<>();
    private final List<Long> staffMemberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        for (Long reservationId : reservationIds) {
            jdbcTemplate.update(
                    "delete from notifications "
                            + "where resource_type = 'RESERVATION' "
                            + "and resource_id = ?",
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
        for (Long slotId : slotIds) {
            jdbcTemplate.update(
                    "delete from reservation_slots where id = ?",
                    slotId
            );
        }
        for (Long staffMemberId : staffMemberIds) {
            jdbcTemplate.update("delete from members where id = ?", staffMemberId);
        }
    }

    @Test
    @DisplayName("마감 전 REQUESTED 예약은 조회·처리하지 않는다")
    void beforeDeadline_isNotProcessed() {
        TestReservation data = saveRequestedReservation(false);
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        List<Reservation> targets =
                reservationRepository.findApprovalTimeoutTargets(
                        ReservationStatus.REQUESTED,
                        now,
                        PageRequest.of(0, 100)
                );
        ReservationApprovalTimeoutProcessor.Result result =
                processor.process(data.reservationId(), now);

        assertThat(targets)
                .extracting(Reservation::getId)
                .doesNotContain(data.reservationId());
        assertThat(result)
                .isEqualTo(ReservationApprovalTimeoutProcessor.Result.SKIPPED);
        assertReservationAndSlot(
                data,
                ReservationStatus.REQUESTED,
                ReservationSlotStatus.RESERVED
        );
        assertThat(timeoutEventCount(data.reservationId())).isZero();
        assertThat(rejectedNotificationCount(data.reservationId())).isZero();
    }

    @Test
    @DisplayName("첫 페이지가 가득 차도 커서로 다음 만료 예약을 이어서 조회한다")
    void timeoutTargets_cursorContinuesPastFirstPage() {
        TestReservation first = saveRequestedReservation(true);
        TestReservation second = saveRequestedReservation(true);
        TestReservation third = saveRequestedReservation(true);
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        List<TestReservation> expectedOrder = List.of(first, second, third)
                .stream()
                .sorted(Comparator
                        .comparing((TestReservation data) -> approvalDeadline(data.reservationId()))
                        .thenComparing(TestReservation::reservationId))
                .toList();

        List<Reservation> firstPage = reservationRepository
                .findApprovalTimeoutTargets(
                        ReservationStatus.REQUESTED,
                        now,
                        PageRequest.of(0, 2)
                );
        Reservation last = firstPage.get(firstPage.size() - 1);
        List<Reservation> secondPage = reservationRepository
                .findApprovalTimeoutTargetsAfter(
                        ReservationStatus.REQUESTED,
                        now,
                        last.getApprovalDeadlineAt(),
                        last.getId(),
                        PageRequest.of(0, 2)
                );

        assertThat(firstPage)
                .extracting(Reservation::getId)
                .containsExactly(
                        expectedOrder.get(0).reservationId(),
                        expectedOrder.get(1).reservationId()
                );
        assertThat(secondPage)
                .extracting(Reservation::getId)
                .containsExactly(expectedOrder.get(2).reservationId());
    }

    @Test
    @DisplayName("마감 후 예약을 자동 거절하고 슬롯·이력·알림을 함께 처리한다")
    void afterDeadline_rejectsAndOpensSlotWithEventAndNotification() {
        TestReservation data = saveRequestedReservation(true);
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        ReservationApprovalTimeoutProcessor.Result result =
                processor.process(data.reservationId(), now);

        assertThat(result)
                .isEqualTo(ReservationApprovalTimeoutProcessor.Result.PROCESSED);
        assertReservationAndSlot(
                data,
                ReservationStatus.REJECTED,
                ReservationSlotStatus.OPEN
        );
        assertThat(timeoutEventCount(data.reservationId())).isEqualTo(1L);
        assertThat(rejectedNotificationCount(data.reservationId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select processed_by is null from reservation_events "
                        + "where reservation_id = ? and event_type = ?",
                Boolean.class,
                data.reservationId(),
                ReservationEventType.TIMEOUT_REJECTED.name()
        )).isTrue();
    }

    @Test
    @DisplayName("실패한 예약은 백오프 후 다음 배치에서 다시 조회된다")
    void failedReservation_isDeferredAndRetriedNextBatch() {
        TestReservation data = saveRequestedReservation(true);
        jdbcTemplate.update(
                "delete from reservation_slots where id = ?",
                data.slotId()
        );
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        ReservationApprovalTimeoutSummary summary = batchService.processBatch();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(reservationRepository.findApprovalTimeoutTargets(
                ReservationStatus.REQUESTED,
                now,
                PageRequest.of(0, 100)
        )).extracting(Reservation::getId)
                .doesNotContain(data.reservationId());
        assertThat(reservationRepository.findApprovalTimeoutRetryTargets(
                ReservationStatus.REQUESTED,
                now.plusMinutes(2),
                PageRequest.of(0, 100)
        )).extracting(Reservation::getId)
                .contains(data.reservationId());
    }

    @Test
    @DisplayName("같은 예약을 동시에 두 번 처리해도 전이·이력·알림은 한 번만 생긴다")
    void duplicateProcessing_isIdempotent() throws Exception {
        TestReservation data = saveRequestedReservation(true);
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);

        RaceResult race = runRace(
                () -> processor.process(data.reservationId(), now),
                () -> processor.process(data.reservationId(), now)
        );

        assertThat(race.errors()).isEmpty();
        assertThat(race.processedCount()).isEqualTo(1);
        assertThat(race.skippedCount()).isEqualTo(1);
        assertReservationAndSlot(
                data,
                ReservationStatus.REJECTED,
                ReservationSlotStatus.OPEN
        );
        assertThat(timeoutEventCount(data.reservationId())).isEqualTo(1L);
        assertThat(rejectedNotificationCount(data.reservationId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("병원 승인과 자동 거절이 경합하면 정확히 하나만 성립한다")
    void approveAndTimeout_onlyOneSucceeds() throws Exception {
        TestReservation data = saveRequestedReservation(false);
        Reservation reservation = reservationRepository
                .findById(data.reservationId())
                .orElseThrow();
        LocalDateTime approvalAt = reservation.getApprovalDeadlineAt()
                .minusNanos(1);
        LocalDateTime timeoutAt = reservation.getApprovalDeadlineAt();
        AtomicInteger approveApplied = new AtomicInteger();

        RaceResult race = runRace(
                () -> {
                    transactionTemplate.executeWithoutResult(status -> {
                        int updated = reservationRepository.approveIfRequested(
                                data.reservationId(),
                                data.hospitalId(),
                                ReservationStatus.REQUESTED,
                                ReservationStatus.CONFIRMED,
                                approvalAt,
                                approvalAt
                        );
                        approveApplied.addAndGet(updated);
                    });
                    return null;
                },
                () -> processor.process(data.reservationId(), timeoutAt)
        );

        assertThat(race.errors()).isEmpty();
        assertThat(approveApplied.get() + race.processedCount()).isEqualTo(1);
        ReservationStatus finalStatus = reservationStatus(data.reservationId());
        assertThat(finalStatus).isIn(
                ReservationStatus.CONFIRMED,
                ReservationStatus.REJECTED
        );
        assertThat(slotStatus(data.slotId())).isEqualTo(
                finalStatus == ReservationStatus.CONFIRMED
                        ? ReservationSlotStatus.RESERVED
                        : ReservationSlotStatus.OPEN
        );
        assertThat(timeoutEventCount(data.reservationId()))
                .isEqualTo(finalStatus == ReservationStatus.REJECTED ? 1L : 0L);
        assertThat(rejectedNotificationCount(data.reservationId()))
                .isEqualTo(finalStatus == ReservationStatus.REJECTED ? 1L : 0L);
    }

    @Test
    @DisplayName("병원 거절과 자동 거절이 경합해도 슬롯은 한 번만 반환된다")
    void manualRejectAndTimeout_onlyOneOpensSlot() throws Exception {
        TestReservation data = saveRequestedReservation(true);
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        AtomicInteger manualApplied = new AtomicInteger();

        // 수동 거절을 리포지토리 직접 호출이 아니라 운영과 같은 서비스 경로로 실행한다 — 그래야
        // 어느 쪽이 이겨도 거절 알림이 정확히 1건인지를 실제 동작으로 검증할 수 있다(PR #107 리뷰 P2).
        Long staffMemberId = saveHospitalStaff(data.hospitalId());

        RaceResult race = runRace(
                () -> {
                    try {
                        hospitalReservationService.reject(
                                staffMemberId,
                                data.reservationId(),
                                ReservationRejectReason.OTHER
                        );
                        manualApplied.incrementAndGet();
                    } catch (ServiceException exception) {
                        // 자동 거절이 먼저 성립하면 조건부 UPDATE가 0행이 되어 INVALID_STATUS로 물러난다(정상 경합 패배).
                        if (exception.getErrorCode() != ReservationErrorCode.INVALID_STATUS) {
                            throw exception;
                        }
                    }
                    return null;
                },
                () -> processor.process(data.reservationId(), now)
        );

        assertThat(race.errors()).isEmpty();
        assertThat(manualApplied.get() + race.processedCount()).isEqualTo(1);
        assertReservationAndSlot(
                data,
                ReservationStatus.REJECTED,
                ReservationSlotStatus.OPEN
        );
        // 이력(TIMEOUT_REJECTED)은 자동 거절만 남기므로 자동이 이긴 경우에만 1건이다.
        assertThat(timeoutEventCount(data.reservationId()))
                .isEqualTo(race.processedCount());
        // 알림은 수동(publishRejected)·자동(publishAutoRejected) 어느 쪽이 이겨도 정확히 1건이어야 한다.
        assertThat(rejectedNotificationCount(data.reservationId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("다른 인스턴스가 DB 잠금을 잡고 있으면 두 번째 실행은 스킵한다")
    void databaseLock_allowsOnlyOneInstance() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Optional<Boolean>> first = executor.submit(() ->
                    timeoutLock.executeIfAcquired(0, () -> {
                        acquired.countDown();
                        await(release);
                        return true;
                    })
            );

            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            Optional<Boolean> second = timeoutLock.executeIfAcquired(
                    0,
                    () -> true
            );

            assertThat(second).isEmpty();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(true);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("마감 조회용 복합 인덱스는 상태와 마감 시각 순서로 구성된다")
    void timeoutIndex_hasExpectedColumns() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                select column_name
                  from information_schema.statistics
                 where table_schema = database()
                   and table_name = 'reservations'
                   and index_name = 'idx_reservations_status_approval_deadline'
                 order by seq_in_index
                """,
                String.class
        );

        assertThat(columns).containsExactly(
                "status",
                "approval_deadline_at"
        );
    }

    // 해당 병원 소속 스태프를 만든다. 가입은 항상 GUARDIAN이므로(Member.createGuardian) 역할·병원은 직접 세팅한다.
    private Long saveHospitalStaff(Long hospitalId) {
        Member staff = Member.createGuardian(
                "timeout-race-staff-" + System.nanoTime() + "@example.com",
                "encoded-password",
                "병원스태프"
        );
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        staff = memberRepository.saveAndFlush(staff);
        staffMemberIds.add(staff.getId());
        return staff.getId();
    }

    private TestReservation saveRequestedReservation(boolean expired) {
        long hospitalId = Math.abs(System.nanoTime());
        long guardianId = hospitalId + 1;
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID).withNano(0);
        LocalDateTime requestedAt = expired ? now.minusHours(2) : now;
        LocalDateTime slotStartAt = expired
                ? now.plusHours(3)
                : now.plusDays(1);

        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                slotStartAt,
                slotStartAt.plusMinutes(30)
        );
        slot.reserve();
        slot = slotRepository.saveAndFlush(slot);
        slotIds.add(slot.getId());

        Reservation reservation = Reservation.request(
                guardianId,
                1L,
                hospitalId,
                slot.getId(),
                1L,
                "초코",
                "DOG",
                requestedAt,
                slotStartAt
        );
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationIds.add(reservation.getId());

        return new TestReservation(
                guardianId,
                hospitalId,
                reservation.getId(),
                slot.getId()
        );
    }

    private RaceResult runRace(Callable<?> first, Callable<?> second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (Callable<?> task : List.of(first, second)) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    Object result = task.call();
                    if (result == ReservationApprovalTimeoutProcessor.Result.PROCESSED) {
                        processed.incrementAndGet();
                    } else if (result == ReservationApprovalTimeoutProcessor.Result.SKIPPED) {
                        skipped.incrementAndGet();
                    }
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

        return new RaceResult(processed.get(), skipped.get(), errors);
    }

    private void assertReservationAndSlot(
            TestReservation data,
            ReservationStatus expectedReservation,
            ReservationSlotStatus expectedSlot
    ) {
        assertThat(reservationStatus(data.reservationId()))
                .isEqualTo(expectedReservation);
        assertThat(slotStatus(data.slotId())).isEqualTo(expectedSlot);
    }

    private ReservationStatus reservationStatus(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow()
                .getStatus();
    }

    private ReservationSlotStatus slotStatus(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow()
                .getStatus();
    }

    private long timeoutEventCount(Long reservationId) {
        return eventRepository.countByReservation_IdAndEventType(
                reservationId,
                ReservationEventType.TIMEOUT_REJECTED
        );
    }

    private long rejectedNotificationCount(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications "
                        + "where resource_type = 'RESERVATION' "
                        + "and resource_id = ? and type = ?",
                Long.class,
                reservationId,
                NotificationType.RESERVATION_REJECTED.name()
        );
    }

    private LocalDateTime approvalDeadline(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow()
                .getApprovalDeadlineAt();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("DB 잠금 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record TestReservation(
            Long guardianId,
            Long hospitalId,
            Long reservationId,
            Long slotId
    ) {
    }

    private record RaceResult(
            int processedCount,
            int skippedCount,
            List<Throwable> errors
    ) {
    }
}
