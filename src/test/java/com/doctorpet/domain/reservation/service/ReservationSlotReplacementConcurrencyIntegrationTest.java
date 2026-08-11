package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationSlotCreateCommand;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "ai.openai.api-key=test-key",
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class ReservationSlotReplacementConcurrencyIntegrationTest {

    private static final int RACE_ATTEMPTS = 5;

    @Autowired
    private ReservationApplicationService reservationApplicationService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PetProfileRepository petProfileRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> slotIds = new ArrayList<>();
    private Long memberId;
    private Long petId;
    private Long paymentMethodId;

    @BeforeEach
    void setUp() {
        Member member = memberRepository.saveAndFlush(Member.createGuardian(
                "slot-replacement-" + System.nanoTime() + "@example.com",
                "encoded-password",
                "슬롯교체동시성"
        ));
        memberId = member.getId();

        PetProfile pet = petProfileRepository.saveAndFlush(PetProfile.create(
                memberId,
                "초코",
                PetSpecies.DOG,
                3,
                new BigDecimal("5.40"),
                true
        ));
        petId = pet.getId();

        PaymentMethod paymentMethod = paymentMethodRepository.saveAndFlush(PaymentMethod.issue(
                memberId,
                "v1:test-encrypted",
                "TEST",
                "1234"
        ));
        paymentMethodId = paymentMethod.getId();
    }

    @AfterEach
    void cleanUp() {
        for (Long slotId : slotIds) {
            jdbcTemplate.update("delete from reservations where slot_id = ?", slotId);
        }
        if (memberId != null) {
            jdbcTemplate.update("delete from reservations where member_id = ?", memberId);
        }
        if (!slotIds.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(slotIds.size(), "?"));
            jdbcTemplate.update(
                    "delete from reservation_slots where id in (" + placeholders + ")",
                    slotIds.toArray()
            );
        }
        if (petId != null) {
            jdbcTemplate.update("delete from pet_profiles where id = ?", petId);
        }
        if (paymentMethodId != null) {
            jdbcTemplate.update("delete from payment_methods where id = ?", paymentMethodId);
        }
        if (memberId != null) {
            jdbcTemplate.update("delete from members where id = ?", memberId);
        }
    }

    @Test
    @DisplayName("동일 슬롯의 예약과 진료시간 재배치가 경합하면 정확히 한 작업만 성공한다")
    void concurrentReservationAndReplacement_onlyOneSucceeds() throws InterruptedException {
        for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
            long hospitalId = System.nanoTime();
            LocalDate businessDate = LocalDate.now().plusDays(3 + attempt);
            LocalDateTime startAt = businessDate.atTime(10, 0);
            ReservationSlot original = reservationSlotRepository.saveAndFlush(
                    ReservationSlot.create(hospitalId, startAt, startAt.plusMinutes(30), businessDate)
            );
            slotIds.add(original.getId());

            ReservationRequest request = new ReservationRequest(
                    petId,
                    original.getId(),
                    paymentMethodId
            );
            List<ReservationSlotCreateCommand> replacements = List.of(
                    new ReservationSlotCreateCommand(
                            startAt.plusHours(1),
                            startAt.plusHours(1).plusMinutes(30)
                    )
            );
            AtomicBoolean reservationSucceeded = new AtomicBoolean();
            AtomicBoolean replacementSucceeded = new AtomicBoolean();
            List<Throwable> unexpected = new CopyOnWriteArrayList<>();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            executor.submit(() -> runConcurrent(
                    ready,
                    start,
                    done,
                    () -> {
                        reservationApplicationService.request(memberId, request);
                        reservationSucceeded.set(true);
                    },
                    unexpected
            ));
            executor.submit(() -> runConcurrent(
                    ready,
                    start,
                    done,
                    () -> {
                        reservationService.replaceOpenSlots(hospitalId, businessDate, replacements);
                        replacementSucceeded.set(true);
                    },
                    unexpected
            ));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(unexpected).isEmpty();
            assertThat(reservationSucceeded.get() ^ replacementSucceeded.get()).isTrue();

            if (reservationSucceeded.get()) {
                assertThat(reservationRepository.countBySlotId(original.getId())).isEqualTo(1);
                assertThat(reservationSlotRepository.findById(original.getId()))
                        .get()
                        .extracting(ReservationSlot::getStatus)
                        .isEqualTo(ReservationSlotStatus.RESERVED);
            } else {
                assertThat(reservationRepository.countBySlotId(original.getId())).isZero();
                assertThat(reservationSlotRepository.findById(original.getId())).isEmpty();
                ReservationSlot replacement = reservationSlotRepository
                        .findBusinessDateSlots(hospitalId, businessDate)
                        .get(0);
                slotIds.add(replacement.getId());
                assertThat(replacement.getStatus()).isEqualTo(ReservationSlotStatus.OPEN);
                assertThat(replacement.getStartAt()).isEqualTo(startAt.plusHours(1));
            }
        }
    }

    @Test
    @DisplayName("동일 슬롯의 예약과 임시 휴무용 슬롯 제거가 경합하면 정확히 한 작업만 성공한다")
    void concurrentReservationAndTemporaryClosureSlotRemoval_onlyOneSucceeds()
            throws InterruptedException {
        for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
            long hospitalId = System.nanoTime();
            LocalDate businessDate = LocalDate.now().plusDays(8 + attempt);
            ReservationSlot original = saveSlot(hospitalId, businessDate, 10);
            ReservationRequest request = new ReservationRequest(
                    petId,
                    original.getId(),
                    paymentMethodId
            );
            AtomicBoolean reservationSucceeded = new AtomicBoolean();
            AtomicBoolean removalSucceeded = new AtomicBoolean();
            List<Throwable> unexpected = new CopyOnWriteArrayList<>();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            executor.submit(() -> runConcurrent(
                    ready,
                    start,
                    done,
                    () -> {
                        reservationApplicationService.request(memberId, request);
                        reservationSucceeded.set(true);
                    },
                    unexpected
            ));
            executor.submit(() -> runConcurrent(
                    ready,
                    start,
                    done,
                    () -> removalSucceeded.set(
                            reservationService.removeOpenSlotsIfNoReservation(
                                    hospitalId,
                                    businessDate
                            )
                    ),
                    unexpected
            ));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(unexpected).isEmpty();
            assertThat(reservationSucceeded.get() ^ removalSucceeded.get()).isTrue();
            if (reservationSucceeded.get()) {
                assertThat(reservationRepository.countBySlotId(original.getId())).isEqualTo(1);
                assertThat(reservationSlotRepository.findById(original.getId()))
                        .get()
                        .extracting(ReservationSlot::getStatus)
                        .isEqualTo(ReservationSlotStatus.RESERVED);
            } else {
                assertThat(reservationRepository.countBySlotId(original.getId())).isZero();
                assertThat(reservationSlotRepository.findById(original.getId())).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("공개 범위를 잠그면 아직 재배치하지 않은 날짜에도 예약이 끼어들 수 없다")
    void replacementRangeLock_blocksReservationOnLaterDate() throws Exception {
        long hospitalId = System.nanoTime();
        LocalDate fromDate = LocalDate.now().plusDays(4);
        LocalDate toDate = fromDate.plusDays(2);
        List<ReservationSlot> originalSlots = List.of(
                saveSlot(hospitalId, fromDate, 9),
                saveSlot(hospitalId, fromDate.plusDays(1), 9),
                saveSlot(hospitalId, toDate, 9)
        );
        ReservationSlot laterSlot = originalSlots.get(2);
        ReservationRequest request = new ReservationRequest(
                petId,
                laterSlot.getId(),
                paymentMethodId
        );
        CountDownLatch rangeLocked = new CountDownLatch(1);
        CountDownLatch continueReplacement = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<Throwable> replacementFuture = executor.submit(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    reservationService.lockOpenSlotsForReplacement(
                            hospitalId,
                            fromDate,
                            toDate
                    );
                    rangeLocked.countDown();
                    await(continueReplacement);
                    for (LocalDate businessDate = fromDate;
                         !businessDate.isAfter(toDate);
                         businessDate = businessDate.plusDays(1)) {
                        reservationService.replaceOpenSlots(
                                hospitalId,
                                businessDate,
                                replacementAt(businessDate, 11)
                        );
                    }
                });
                return null;
            } catch (Throwable throwable) {
                return throwable;
            }
        });

        assertThat(rangeLocked.await(10, TimeUnit.SECONDS)).isTrue();
        Future<Throwable> reservationFuture = executor.submit(() -> {
            try {
                reservationApplicationService.request(memberId, request);
                return null;
            } catch (Throwable throwable) {
                return throwable;
            }
        });

        assertThatThrownBy(() -> reservationFuture.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        continueReplacement.countDown();

        assertThat(replacementFuture.get(30, TimeUnit.SECONDS)).isNull();
        assertThat(reservationFuture.get(30, TimeUnit.SECONDS))
                .isInstanceOf(ServiceException.class);
        assertThat(reservationRepository.countBySlotId(laterSlot.getId())).isZero();
        assertThat(reservationSlotRepository.findById(laterSlot.getId())).isEmpty();

        for (LocalDate businessDate = fromDate;
             !businessDate.isAfter(toDate);
             businessDate = businessDate.plusDays(1)) {
            ReservationSlot replacement = reservationSlotRepository
                    .findBusinessDateSlots(hospitalId, businessDate)
                    .get(0);
            slotIds.add(replacement.getId());
            assertThat(replacement.getStartAt()).isEqualTo(businessDate.atTime(11, 0));
        }
        executor.shutdown();
    }

    @Test
    @DisplayName("적용일부터 공개 마지막 날까지 재배치 중 한 날짜에 예약이 있으면 전체 범위를 롤백한다")
    void replacementRange_reservedDate_rollsBackEntireRange() {
        long hospitalId = System.nanoTime();
        LocalDate today = LocalDate.now();
        LocalDate effectiveFrom = today.plusDays(11);
        LocalDate publishedUntil = today.plusDays(13);
        List<ReservationSlot> originalSlots = new ArrayList<>();
        for (LocalDate businessDate = effectiveFrom;
             !businessDate.isAfter(publishedUntil);
             businessDate = businessDate.plusDays(1)) {
            originalSlots.add(saveSlot(hospitalId, businessDate, 9));
        }
        ReservationSlot lastDay = originalSlots.get(originalSlots.size() - 1);
        lastDay.reserve();
        reservationSlotRepository.saveAndFlush(lastDay);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            for (LocalDate businessDate = effectiveFrom;
                 !businessDate.isAfter(publishedUntil);
                 businessDate = businessDate.plusDays(1)) {
                reservationService.replaceOpenSlots(
                        hospitalId,
                        businessDate,
                        replacementAt(businessDate, 11)
                );
            }
        })).isInstanceOf(ServiceException.class);

        for (ReservationSlot originalSlot : originalSlots.subList(0, originalSlots.size() - 1)) {
            assertThat(reservationSlotRepository.findById(originalSlot.getId()))
                    .get()
                    .extracting(ReservationSlot::getStatus)
                    .isEqualTo(ReservationSlotStatus.OPEN);
        }
        assertThat(reservationSlotRepository.findById(lastDay.getId()))
                .get()
                .extracting(ReservationSlot::getStatus)
                .isEqualTo(ReservationSlotStatus.RESERVED);
        for (LocalDate businessDate = effectiveFrom;
             !businessDate.isAfter(publishedUntil);
             businessDate = businessDate.plusDays(1)) {
            assertThat(reservationSlotRepository.findBusinessDateSlots(hospitalId, businessDate))
                    .extracting(ReservationSlot::getStartAt)
                    .containsExactly(businessDate.atTime(9, 0));
        }
    }

    @Test
    @DisplayName("마지막 공개 영업일의 자정 이후 야간 예약도 조회하고 잠근다")
    void lastPublishedOvernightReservationUsesBusinessDateRange() {
        long hospitalId = System.nanoTime();
        LocalDate today = LocalDate.now();
        LocalDate businessDate = today.plusDays(13);
        ReservationSlot overnight = ReservationSlot.create(
                hospitalId,
                businessDate.plusDays(1).atTime(1, 0),
                businessDate.plusDays(1).atTime(1, 30),
                businessDate
        );
        overnight.reserve();
        reservationSlotRepository.saveAndFlush(overnight);
        slotIds.add(overnight.getId());

        assertThat(reservationService.findLatestReservedBusinessDate(
                hospitalId,
                today,
                businessDate
        )).contains(businessDate);
        assertThatThrownBy(() -> reservationService.lockOpenSlotsForReplacement(
                hospitalId,
                today,
                businessDate
        )).isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(com.doctorpet.domain.reservation.exception.SlotErrorCode.ALREADY_RESERVED);
    }

    private ReservationSlot saveSlot(Long hospitalId, LocalDate businessDate, int hour) {
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                hospitalId,
                businessDate.atTime(hour, 0),
                businessDate.atTime(hour, 30),
                businessDate
        ));
        slotIds.add(slot.getId());
        return slot;
    }

    private List<ReservationSlotCreateCommand> replacementAt(LocalDate businessDate, int hour) {
        return List.of(new ReservationSlotCreateCommand(
                businessDate.atTime(hour, 0),
                businessDate.atTime(hour, 30)
        ));
    }

    private void runConcurrent(
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            Runnable action,
            List<Throwable> unexpected
    ) {
        ready.countDown();
        try {
            start.await();
            action.run();
        } catch (ServiceException ignored) {
            // 예약 또는 재배치 중 뒤늦게 진 쪽은 슬롯 충돌로 실패하는 것이 정상이다.
        } catch (Throwable throwable) {
            unexpected.add(throwable);
        } finally {
            done.countDown();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("재배치 계속 신호를 기다리다 시간 초과했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재배치 대기 중 인터럽트가 발생했습니다.", exception);
        }
    }
}
