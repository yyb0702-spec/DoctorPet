package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ReservationConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 10;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdSlotId;

    @AfterEach
    void cleanUp() {
        if (createdSlotId != null) {
            jdbcTemplate.update(
                    "delete from reservations where slot_id = ?",
                    createdSlotId
            );
            jdbcTemplate.update(
                    "delete from reservation_slots where id = ?",
                    createdSlotId
            );
        }
    }

    @Test
    @DisplayName("동일 슬롯에 동시 예약하면 한 건만 성공하고 나머지는 SLOT_002로 실패한다")
    void concurrentRequest_sameSlot_onlyOneSucceeds() throws InterruptedException {
        long hospitalId = System.nanoTime();
        LocalDateTime startAt = LocalDateTime.now().plusDays(2).withNano(0);
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(
                ReservationSlot.create(
                        hospitalId,
                        startAt,
                        startAt.plusMinutes(30)
                )
        );
        createdSlotId = slot.getId();
        ReservationRequest request = new ReservationRequest(
                1L,
                slot.getId(),
                1L,
                "초코",
                "DOG"
        );
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            long memberId = i + 1L;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reservationService.request(memberId, request);
                    successCount.incrementAndGet();
                } catch (ServiceException exception) {
                    if (exception.getErrorCode() == SlotErrorCode.ALREADY_RESERVED) {
                        conflictCount.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(REQUEST_COUNT - 1);
        assertThat(reservationRepository.countBySlotId(slot.getId())).isEqualTo(1);

        ReservationSlot reloaded = reservationSlotRepository.findById(slot.getId())
                .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReservationSlotStatus.RESERVED);
    }
}
