package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.math.BigDecimal;
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

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class ReservationConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 10;

    @Autowired
    private ReservationApplicationService reservationApplicationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private PetProfileRepository petProfileRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdSlotId;
    private Long createdPetId;
    private Long createdPaymentMethodId;
    private Long createdMemberId;

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
        if (createdPetId != null) {
            jdbcTemplate.update(
                    "delete from pet_profiles where id = ?",
                    createdPetId
            );
        }
        if (createdPaymentMethodId != null) {
            jdbcTemplate.update(
                    "delete from payment_methods where id = ?",
                    createdPaymentMethodId
            );
        }
        if (createdMemberId != null) {
            jdbcTemplate.update(
                    "delete from members where id = ?",
                    createdMemberId
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

        Member member = memberRepository.saveAndFlush(
                Member.createGuardian(
                        "reservation-concurrency-" + System.nanoTime()
                                + "@example.com",
                        "encoded-password",
                        "동시성테스트"
                )
        );
        createdMemberId = member.getId();

        PetProfile pet = petProfileRepository.saveAndFlush(
                PetProfile.create(
                        member.getId(),
                        "초코",
                        PetSpecies.DOG,
                        3,
                        new BigDecimal("5.40"),
                        true
                )
        );
        createdPetId = pet.getId();

        PaymentMethod paymentMethod = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(
                        member.getId(),
                        "v1:test-encrypted",
                        "TEST",
                        "1234"
                )
        );
        createdPaymentMethodId = paymentMethod.getId();

        ReservationRequest request = new ReservationRequest(
                pet.getId(),
                slot.getId(),
                paymentMethod.getId()
        );
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < REQUEST_COUNT; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    reservationApplicationService.request(member.getId(), request);
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
