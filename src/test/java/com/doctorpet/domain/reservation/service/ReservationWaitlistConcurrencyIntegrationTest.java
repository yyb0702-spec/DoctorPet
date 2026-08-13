package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
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
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private ReservationWaitlistRepository reservationWaitlistRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    private RaceResult runRace(Runnable first, Runnable second) throws InterruptedException {
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
                    if (!isExpectedRaceLoss(throwable)) {
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

    private record RaceResult(int successCount, List<Throwable> unexpectedErrors) {
    }
}
