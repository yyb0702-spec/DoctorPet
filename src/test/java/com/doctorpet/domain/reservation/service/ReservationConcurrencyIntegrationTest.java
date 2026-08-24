package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
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
    private HospitalRepository hospitalRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long createdSlotId;
    private Long createdPetId;
    private Long createdPaymentMethodId;
    private Long createdMemberId;
    private Long createdHospitalId;

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
        if (createdHospitalId != null) {
            hospitalRepository.deleteById(createdHospitalId);
        }
    }

    @Test
    @DisplayName("동일 슬롯에 동시 예약하면 한 건만 성공하고 나머지는 SLOT_002로 실패한다")
    void concurrentRequest_sameSlot_onlyOneSucceeds() throws InterruptedException {
        Hospital hospital = Hospital.createFromPublicData(
                "RESERVATION-CONCURRENCY-" + System.nanoTime(),
                "TEST-LOCAL-GOV",
                "예약 동시성 테스트 병원",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
        hospital.markAsPartner();
        long hospitalId = hospitalRepository.saveAndFlush(hospital).getId();
        createdHospitalId = hospitalId;
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

    @Test
    @DisplayName("휴업 갱신이 병원 락을 먼저 잡으면 신규 예약은 실패한다")
    void requestAfterBusinessStatusChangeLock_failsWithoutOccupyingSlot()
            throws InterruptedException {
        Hospital hospital = Hospital.createFromPublicData(
                "RESERVATION-STATUS-RACE-" + System.nanoTime(),
                "TEST-LOCAL-GOV",
                "예약 상태 경합 테스트 병원",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
        hospital.markAsPartner();
        hospital = hospitalRepository.saveAndFlush(hospital);
        createdHospitalId = hospital.getId();

        LocalDateTime startAt = LocalDateTime.now().plusDays(2).withNano(0);
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(
                ReservationSlot.create(
                        hospital.getId(),
                        startAt,
                        startAt.plusMinutes(30)
                )
        );
        createdSlotId = slot.getId();

        Member member = memberRepository.saveAndFlush(
                Member.createGuardian(
                        "reservation-status-race-" + System.nanoTime()
                                + "@example.com",
                        "encoded-password",
                        "상태경합테스트"
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
        CountDownLatch hospitalLocked = new CountDownLatch(1);
        CountDownLatch requestStarted = new CountDownLatch(1);
        AtomicReference<Throwable> updateError = new AtomicReference<>();
        AtomicReference<Throwable> requestError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Hospital lockedHospital = hospitalRepository
                            .findByIdForUpdate(createdHospitalId)
                            .orElseThrow();
                    ReflectionTestUtils.setField(
                            lockedHospital,
                            "businessStatus",
                            BusinessStatus.CLOSED_TEMP
                    );
                    hospitalLocked.countDown();
                    await(requestStarted);
                });
            } catch (Throwable throwable) {
                updateError.set(throwable);
                hospitalLocked.countDown();
            }
        });

        executor.submit(() -> {
            try {
                if (!hospitalLocked.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("병원 락 획득 대기 시간 초과");
                }
                requestStarted.countDown();
                reservationApplicationService.request(member.getId(), request);
            } catch (Throwable throwable) {
                requestError.set(throwable);
            }
        });

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(updateError.get()).isNull();
        assertThat(requestError.get())
                .isInstanceOf(ServiceException.class)
                .extracting(throwable ->
                        ((ServiceException) throwable).getErrorCode()
                )
                .isEqualTo(
                        HospitalErrorCode.HOSPITAL_RESERVATION_NOT_AVAILABLE
                );
        assertThat(reservationRepository.countBySlotId(slot.getId())).isZero();
        assertThat(reservationSlotRepository.findById(slot.getId()).orElseThrow()
                .getStatus()).isEqualTo(ReservationSlotStatus.OPEN);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
