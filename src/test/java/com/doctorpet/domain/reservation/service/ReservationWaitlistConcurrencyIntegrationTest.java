package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.exception.ReservationWaitlistErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Level 3 — 실제 MySQL에서 대기열 승급 경쟁 시 한 건만 OFFERED가 되는지 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3307/doctorpet?serverTimezone=Asia/Seoul&characterEncoding=UTF-8}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:root}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:root}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host=localhost",
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "jwt.secret=doctorpet-waitlist-concurrency-test-secret-key-32-bytes-minimum",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class ReservationWaitlistConcurrencyIntegrationTest {

    @Autowired
    private ReservationSlotReleaseService reservationSlotReleaseService;

    @Autowired
    private ReservationWaitlistService reservationWaitlistService;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private ReservationWaitlistRepository reservationWaitlistRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ReservationApplicationService reservationApplicationService;

    private Long slotId;

    @AfterEach
    void cleanUp() {
        if (slotId == null) {
            return;
        }
        jdbcTemplate.update("""
                delete from notifications
                 where resource_type = 'RESERVATION_WAITLIST'
                   and resource_id in (select id from reservation_waitlists where slot_id = ?)
                """, slotId);
        jdbcTemplate.update("delete from reservation_waitlists where slot_id = ?", slotId);
        jdbcTemplate.update("delete from reservation_slots where id = ?", slotId);
    }

    @Test
    @DisplayName("동일 슬롯 반환이 동시에 실행돼도 FIFO 첫 대기자 한 명만 OFFERED가 되고 슬롯은 RESERVED다")
    void concurrentRelease_offersExactlyOneFifoCandidate() throws InterruptedException {
        ReservationSlot slot = ReservationSlot.create(
                System.nanoTime(),
                LocalDateTime.now(SEOUL_ZONE_ID).plusDays(2).withNano(0),
                LocalDateTime.now(SEOUL_ZONE_ID).plusDays(2).withNano(0).plusMinutes(30)
        );
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        ReservationWaitlist first = reservationWaitlistRepository.saveAndFlush(
                ReservationWaitlist.waiting(101L, slotId)
        );
        ReservationWaitlist second = reservationWaitlistRepository.saveAndFlush(
                ReservationWaitlist.waiting(102L, slotId)
        );

        RaceResult result = runRace(
                () -> reservationSlotReleaseService.release(slotId),
                () -> reservationSlotReleaseService.release(slotId)
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.unexpectedErrors()).isEmpty();
        List<ReservationWaitlist> waiting = reservationWaitlistRepository
                .findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                        slotId,
                        ReservationWaitlistStatus.WAITING
                );
        Integer offeredCount = jdbcTemplate.queryForObject(
                "select count(*) from reservation_waitlists where slot_id = ? and status = 'OFFERED'",
                Integer.class,
                slotId
        );
        Long offeredId = jdbcTemplate.queryForObject(
                "select id from reservation_waitlists where slot_id = ? and status = 'OFFERED'",
                Long.class,
                slotId
        );
        assertThat(offeredCount).isEqualTo(1);
        assertThat(offeredId).isEqualTo(first.getId());
        assertThat(waiting).extracting(ReservationWaitlist::getId).containsExactly(second.getId());
        assertThat(reservationSlotRepository.findById(slotId).orElseThrow().getStatus())
                .isEqualTo(ReservationSlotStatus.RESERVED);
        Integer offeredNotificationCount = jdbcTemplate.queryForObject(
                "select count(*) from notifications "
                        + "where type = 'RESERVATION_WAITLIST_OFFERED' "
                        + "and resource_type = 'RESERVATION_WAITLIST' and resource_id = ?",
                Integer.class,
                first.getId()
        );
        assertThat(offeredNotificationCount).isEqualTo(1);
    }

    @Test
    @DisplayName("수락과 만료가 경합하면 ACCEPTED만 성립하고 슬롯 반환·다음 승급은 실행되지 않는다")
    void acceptAndExpireRace_onlyAcceptSucceedsWithoutReleasingSlot() throws Exception {
        WaitlistRaceData data = saveOfferedWaitlistWithNextWaiting();
        given(reservationApplicationService.requestFromWaitlist(
                data.memberId(), 300L, 400L, data.slotId()))
                .willReturn(new ReservationResponse(
                        900L, data.memberId(), data.hospitalId(), data.slotId(),
                        ReservationStatus.REQUESTED, LocalDateTime.now(SEOUL_ZONE_ID)));

        Throwable raceFailure = runStaleTransitionRace(
                data.waitlistId(),
                // MySQL DATETIME(6) 저장 시각보다 확실히 뒤여야 만료 전이 자체는 유효하고,
                // 이후 flush에서 version 충돌만 검증할 수 있다.
                waitlist -> waitlist.expire(data.expiresAt().plusSeconds(1))
        );

        assertOptimisticRaceLoss(raceFailure);
        assertAcceptedWithoutSlotRelease(data);
    }

    @Test
    @DisplayName("수락과 거절이 경합하면 ACCEPTED만 성립하고 슬롯 반환·다음 승급은 실행되지 않는다")
    void acceptAndRejectRace_onlyAcceptSucceedsWithoutReleasingSlot() throws Exception {
        WaitlistRaceData data = saveOfferedWaitlistWithNextWaiting();
        given(reservationApplicationService.requestFromWaitlist(
                data.memberId(), 300L, 400L, data.slotId()))
                .willReturn(new ReservationResponse(
                        900L, data.memberId(), data.hospitalId(), data.slotId(),
                        ReservationStatus.REQUESTED, LocalDateTime.now(SEOUL_ZONE_ID)));

        Throwable raceFailure = runStaleTransitionRace(
                data.waitlistId(),
                waitlist -> waitlist.reject(LocalDateTime.now(SEOUL_ZONE_ID))
        );

        assertOptimisticRaceLoss(raceFailure);
        assertAcceptedWithoutSlotRelease(data);
    }

    @Test
    @DisplayName("대기열 등록과 슬롯 반환이 경합해도 OPEN 슬롯에 WAITING 대기열이 남지 않는다")
    void registerAndReleaseRace_neverLeavesWaitingOnOpenSlot() throws InterruptedException {
        ReservationSlot slot = ReservationSlot.create(
                System.nanoTime(),
                LocalDateTime.now(SEOUL_ZONE_ID).plusDays(2).withNano(0),
                LocalDateTime.now(SEOUL_ZONE_ID).plusDays(2).withNano(0).plusMinutes(30)
        );
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        RaceResult result = runRace(
                () -> reservationWaitlistService.register(501L, slotId),
                () -> reservationSlotReleaseService.release(slotId),
                this::isExpectedRegisterReleaseRaceLoss
        );

        assertThat(result.unexpectedErrors()).isEmpty();
        Integer invalidStateCount = jdbcTemplate.queryForObject("""
                select count(*)
                  from reservation_slots slot
                 where slot.id = ?
                   and slot.status = 'OPEN'
                   and exists (
                        select 1
                          from reservation_waitlists waitlist
                         where waitlist.slot_id = slot.id
                           and waitlist.status = 'WAITING'
                   )
                """, Integer.class, slotId);
        assertThat(invalidStateCount).isZero();
    }

    private WaitlistRaceData saveOfferedWaitlistWithNextWaiting() {
        long hospitalId = System.nanoTime();
        LocalDateTime startAt = LocalDateTime.now(SEOUL_ZONE_ID).plusDays(2).withNano(0);
        ReservationSlot slot = ReservationSlot.create(hospitalId, startAt, startAt.plusMinutes(30));
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        LocalDateTime offeredAt = LocalDateTime.now(SEOUL_ZONE_ID).minusMinutes(1);
        LocalDateTime expiresAt = offeredAt.plusMinutes(10);
        ReservationWaitlist offered = ReservationWaitlist.waiting(201L, slotId);
        offered.offer(offeredAt, expiresAt);
        offered = reservationWaitlistRepository.saveAndFlush(offered);
        reservationWaitlistRepository.saveAndFlush(ReservationWaitlist.waiting(202L, slotId));
        return new WaitlistRaceData(offered.getId(), slotId, hospitalId, 201L, expiresAt);
    }

    private Throwable runStaleTransitionRace(
            Long waitlistId,
            java.util.function.Consumer<ReservationWaitlist> staleTransition
    ) throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch staleEntityLoaded = new CountDownLatch(1);
        CountDownLatch allowStaleFlush = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var staleTransitionFuture = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                ReservationWaitlist staleWaitlist = reservationWaitlistRepository.findById(waitlistId)
                        .orElseThrow();
                staleEntityLoaded.countDown();
                try {
                    assertThat(allowStaleFlush.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                staleTransition.accept(staleWaitlist);
                reservationWaitlistRepository.flush();
                reservationSlotReleaseService.release(staleWaitlist.getSlotId());
            }));
            assertThat(staleEntityLoaded.await(10, TimeUnit.SECONDS)).isTrue();

            reservationWaitlistService.accept(201L, waitlistId, 300L, 400L);
            allowStaleFlush.countDown();
            return catchThrowable(() -> staleTransitionFuture.get(10, TimeUnit.SECONDS));
        } finally {
            allowStaleFlush.countDown();
            executor.shutdownNow();
        }
    }

    private void assertOptimisticRaceLoss(Throwable throwable) {
        assertThat(throwable).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThat(throwable.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
    }

    private void assertAcceptedWithoutSlotRelease(WaitlistRaceData data) {
        assertThat(reservationWaitlistRepository.findById(data.waitlistId()).orElseThrow().getStatus())
                .isEqualTo(ReservationWaitlistStatus.ACCEPTED);
        assertThat(reservationWaitlistRepository
                .findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
                        data.slotId(), ReservationWaitlistStatus.WAITING))
                .hasSize(1);
        assertThat(reservationSlotRepository.findById(data.slotId()).orElseThrow().getStatus())
                .isEqualTo(ReservationSlotStatus.RESERVED);
    }

    private RaceResult runRace(Runnable first, Runnable second) throws InterruptedException {
        return runRace(first, second, this::isExpectedRaceLoss);
    }

    private RaceResult runRace(
            Runnable first,
            Runnable second,
            java.util.function.Predicate<Throwable> isExpectedLoss
    ) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        for (Runnable task : List.of(first, second)) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                    successCount.incrementAndGet();
                } catch (Throwable throwable) {
                    if (!isExpectedLoss.test(throwable)) {
                        unexpectedErrors.add(throwable);
                    }
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
        return new RaceResult(successCount.get(), unexpectedErrors);
    }

    private boolean isExpectedRaceLoss(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }

    private boolean isExpectedRegisterReleaseRaceLoss(Throwable throwable) {
        if (isExpectedRaceLoss(throwable)) {
            return true;
        }
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ServiceException serviceException
                    && serviceException.getErrorCode()
                    == ReservationWaitlistErrorCode.SLOT_NOT_RESERVED) {
                return true;
            }
        }
        return false;
    }

    private record RaceResult(int successCount, List<Throwable> unexpectedErrors) {
    }

    private record WaitlistRaceData(
            Long waitlistId,
            Long slotId,
            Long hospitalId,
            Long memberId,
            LocalDateTime expiresAt
    ) {
    }
}
