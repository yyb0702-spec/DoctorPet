package com.doctorpet.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.review.dto.request.ReviewRequest;
import com.doctorpet.domain.review.exception.ReviewErrorCode;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReviewCreateConcurrencyIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 20;
    private static final Long MEMBER_ID = 95101L;
    private static final Long HOSPITAL_ID = 95201L;

    @Autowired
    private ReviewApplicationService reviewApplicationService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    private Long reservationId;
    private Long paymentId;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = reservationRepository.saveAndFlush(Reservation.request(
                MEMBER_ID,
                95301L,
                HOSPITAL_ID,
                95401L,
                95501L,
                "초코",
                "DOG",
                now,
                now.plusDays(1)
        ));
        reservationId = reservation.getId();

        Payment payment = Payment.pending(
                reservationId,
                "review-concurrency-" + reservationId,
                95501L,
                "VISA",
                "1234",
                50_000
        );
        payment.markPaid("pg-review-" + reservationId, now);
        paymentId = paymentRepository.saveAndFlush(payment).getId();
    }

    @AfterEach
    void tearDown() {
        reviewRepository.findAll().stream()
                .filter(review -> review.getReservationId().equals(reservationId))
                .forEach(reviewRepository::delete);
        paymentRepository.deleteById(paymentId);
        reservationRepository.deleteById(reservationId);
    }

    @Test
    @DisplayName("동일 예약에 동시 리뷰 작성이 몰려도 정확히 1건만 성립한다")
    void concurrentCreate_onlyOneSucceeds() throws InterruptedException {
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        AtomicInteger unexpectedFailure = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    reviewApplicationService.create(
                            MEMBER_ID,
                            reservationId,
                            new ReviewRequest(new BigDecimal("4.5"), "친절했어요.")
                    );
                    success.incrementAndGet();
                } catch (ServiceException e) {
                    if (e.getErrorCode() == ReviewErrorCode.ALREADY_REVIEWED) {
                        duplicate.incrementAndGet();
                    } else {
                        unexpectedFailure.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(success.get()).isEqualTo(1);
        assertThat(duplicate.get()).isEqualTo(CONCURRENT_REQUESTS - 1);
        assertThat(unexpectedFailure.get()).isZero();
        assertThat(reviewRepository.existsByReservationId(reservationId)).isTrue();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getReviewedAt())
                .isNotNull();
    }
}
