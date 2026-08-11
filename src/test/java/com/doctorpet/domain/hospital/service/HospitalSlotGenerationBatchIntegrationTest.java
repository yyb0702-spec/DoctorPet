package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalTemporaryClosureRepository;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "ai.openai.api-key=test-key",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class HospitalSlotGenerationBatchIntegrationTest {

    @Autowired
    private HospitalSlotGenerationBatchService batchService;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalOperatingScheduleRepository scheduleRepository;

    @Autowired
    private HospitalTemporaryClosureRepository closureRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @Autowired
    private HospitalOperatingHoursApplicationService operatingHoursService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<Long> hospitalIds = new ArrayList<>();
    private final List<Long> scheduleIds = new ArrayList<>();
    private final List<Long> closureIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long hospitalId : hospitalIds) {
            List<ReservationSlot> slots = slotRepository.findSlotsInRange(
                    hospitalId,
                    LocalDate.of(2026, 8, 1).atStartOfDay(),
                    LocalDate.of(2026, 9, 1).atStartOfDay()
            );
            slotRepository.deleteAll(slots);
        }
        slotRepository.flush();
        closureRepository.deleteAllById(closureIds);
        closureRepository.flush();
        scheduleRepository.deleteAllById(scheduleIds);
        scheduleRepository.flush();
        hospitalRepository.deleteAllById(hospitalIds);
        hospitalRepository.flush();
        hospitalIds.clear();
        scheduleIds.clear();
        closureIds.clear();
    }

    @Test
    void generateUsesFutureScheduleSkipsClosureAndIsIdempotent() {
        LocalDate businessDate = LocalDate.of(2026, 8, 24);
        Hospital operatingHospital = saveHospital("SLOT-BATCH-OPERATING");
        Hospital closedHospital = saveHospital("SLOT-BATCH-CLOSED");
        Hospital nonPartnerHospital = saveHospital("SLOT-BATCH-NON-PARTNER", false);

        saveSchedule(schedule(
                operatingHospital,
                businessDate.minusDays(10),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(10, 0))
        ));
        saveSchedule(schedule(
                operatingHospital,
                businessDate,
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(11, 0), LocalTime.of(12, 0))
        ));
        saveSchedule(schedule(
                closedHospital,
                businessDate.minusDays(1),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(13, 0), LocalTime.of(14, 0))
        ));
        saveSchedule(schedule(
                nonPartnerHospital,
                businessDate.minusDays(1),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(15, 0), LocalTime.of(16, 0))
        ));
        HospitalTemporaryClosure closure = closureRepository.saveAndFlush(
                HospitalTemporaryClosure.create(closedHospital, businessDate)
        );
        closureIds.add(closure.getId());

        var first = batchService.generateRange(businessDate, businessDate);
        var second = batchService.generateRange(businessDate, businessDate);

        assertThat(first.locked()).isTrue();
        assertThat(first.targetDates()).isEqualTo(1);
        assertThat(first.targetTasks()).isEqualTo(2);
        assertThat(first.succeededTasks()).isEqualTo(2);
        assertThat(first.failedTasks()).isZero();
        assertThat(first.createdSlots()).isEqualTo(2);
        assertThat(second.createdSlots()).isZero();
        assertThat(slotRepository.findBusinessDateSlots(
                operatingHospital.getId(),
                businessDate
        )).extracting(ReservationSlot::getStartAt)
                .containsExactly(
                        businessDate.atTime(11, 0),
                        businessDate.atTime(11, 30)
                );
        assertThat(slotRepository.findBusinessDateSlots(
                closedHospital.getId(),
                businessDate
        )).isEmpty();
        assertThat(slotRepository.findBusinessDateSlots(
                nonPartnerHospital.getId(),
                businessDate
        )).isEmpty();
    }

    @Test
    void generateRangeFillsAllFourteenDaysAndRecoversMissingDates() {
        LocalDate fromDate = LocalDate.of(2026, 8, 11);
        LocalDate toDate = fromDate.plusDays(13);
        LocalDate existingDate = fromDate.plusDays(5);
        Hospital hospital = saveHospital("SLOT-BATCH-RANGE");
        saveSchedule(HospitalOperatingSchedule.create(
                hospital,
                fromDate.minusDays(1),
                allDays(new DailyOperatingHours(
                        LocalTime.of(9, 0),
                        LocalTime.of(10, 0)
                ))
        ));

        var existing = batchService.generateRange(existingDate, existingDate);
        var recovered = batchService.generateRange(fromDate, toDate);
        var repeated = batchService.generateRange(fromDate, toDate);

        assertThat(existing.createdSlots()).isEqualTo(2);
        assertThat(recovered.locked()).isTrue();
        assertThat(recovered.targetDates()).isEqualTo(14);
        assertThat(recovered.targetTasks()).isEqualTo(14);
        assertThat(recovered.failedTasks()).isZero();
        assertThat(recovered.createdSlots()).isEqualTo(26);
        assertThat(repeated.createdSlots()).isZero();
        assertThat(slotRepository.findSlotsInRange(
                hospital.getId(),
                fromDate.atStartOfDay(),
                toDate.plusDays(1).atStartOfDay()
        )).hasSize(28);

        for (LocalDate businessDate = fromDate;
             !businessDate.isAfter(toDate);
             businessDate = businessDate.plusDays(1)) {
            assertThat(slotRepository.findBusinessDateSlots(
                    hospital.getId(),
                    businessDate
            )).hasSize(2);
        }
    }

    @Test
    void slotGenerationWaitsForTemporaryClosureAndLeavesNoOpenSlot() throws Exception {
        LocalDate businessDate = LocalDate.of(2026, 8, 20);
        Hospital hospital = saveHospital("SLOT-CLOSURE-RACE");
        saveSchedule(schedule(
                hospital,
                businessDate.minusDays(1),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(10, 0))
        ));
        CountDownLatch closureStored = new CountDownLatch(1);
        CountDownLatch commitClosure = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> closureFuture = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        Hospital lockedHospital = hospitalRepository
                                .findByIdForUpdate(hospital.getId())
                                .orElseThrow();
                        HospitalTemporaryClosure closure = closureRepository.saveAndFlush(
                                HospitalTemporaryClosure.create(
                                        lockedHospital,
                                        businessDate
                                )
                        );
                        closureIds.add(closure.getId());
                        closureStored.countDown();
                        await(commitClosure);
                    })
            );

            assertThat(closureStored.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> generationFuture = executor.submit(() ->
                    operatingHoursService.createSlots(hospital.getId(), businessDate)
            );

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> generationFuture.get(300, TimeUnit.MILLISECONDS)
            ).isInstanceOf(TimeoutException.class);
            commitClosure.countDown();

            closureFuture.get(30, TimeUnit.SECONDS);
            assertThat(generationFuture.get(30, TimeUnit.SECONDS)).isZero();
            assertThat(slotRepository.findBusinessDateSlots(
                    hospital.getId(),
                    businessDate
            )).isEmpty();
        } finally {
            commitClosure.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void slotGenerationWaitsForScheduleChangeAndUsesChangedHours() throws Exception {
        LocalDate businessDate = LocalDate.of(2026, 8, 21);
        Hospital hospital = saveHospital("SLOT-SCHEDULE-RACE");
        HospitalOperatingSchedule schedule = schedule(
                hospital,
                businessDate.minusDays(1),
                businessDate.getDayOfWeek(),
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(10, 0))
        );
        saveSchedule(schedule);
        CountDownLatch scheduleChanged = new CountDownLatch(1);
        CountDownLatch commitSchedule = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> updateFuture = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        hospitalRepository.findByIdForUpdate(hospital.getId())
                                .orElseThrow();
                        HospitalOperatingSchedule lockedSchedule = scheduleRepository
                                .findSchedule(hospital.getId(), schedule.getEffectiveFrom())
                                .orElseThrow();
                        lockedSchedule.changeOperatingHours(Map.of(
                                businessDate.getDayOfWeek(),
                                List.of(new DailyOperatingHours(
                                        LocalTime.of(11, 0),
                                        LocalTime.of(12, 0)
                                ))
                        ));
                        scheduleRepository.saveAndFlush(lockedSchedule);
                        scheduleChanged.countDown();
                        await(commitSchedule);
                    })
            );

            assertThat(scheduleChanged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> generationFuture = executor.submit(() ->
                    operatingHoursService.createSlots(hospital.getId(), businessDate)
            );

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> generationFuture.get(300, TimeUnit.MILLISECONDS)
            ).isInstanceOf(TimeoutException.class);
            commitSchedule.countDown();

            updateFuture.get(30, TimeUnit.SECONDS);
            assertThat(generationFuture.get(30, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(slotRepository.findBusinessDateSlots(
                    hospital.getId(),
                    businessDate
            )).extracting(ReservationSlot::getStartAt)
                    .containsExactly(
                            businessDate.atTime(11, 0),
                            businessDate.atTime(11, 30)
                    );
        } finally {
            commitSchedule.countDown();
            executor.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 대기 중 중단되었습니다.", exception);
        }
    }

    private Hospital saveHospital(String managementNumber) {
        return saveHospital(managementNumber, true);
    }

    private Hospital saveHospital(String managementNumber, boolean partner) {
        Hospital hospital = Hospital.createFromPublicData(
                managementNumber + System.nanoTime(),
                "SLOT-BATCH-LOCAL-GOV",
                "슬롯 배치 테스트 병원",
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
        if (partner) {
            hospital.markAsPartner();
        }
        Hospital saved = hospitalRepository.saveAndFlush(hospital);
        hospitalIds.add(saved.getId());
        return saved;
    }

    private HospitalOperatingSchedule schedule(
            Hospital hospital,
            LocalDate effectiveFrom,
            DayOfWeek dayOfWeek,
            DailyOperatingHours operatingHours
    ) {
        return HospitalOperatingSchedule.create(
                hospital,
                effectiveFrom,
                Map.of(dayOfWeek, List.of(operatingHours))
        );
    }

    private void saveSchedule(HospitalOperatingSchedule schedule) {
        scheduleIds.add(scheduleRepository.saveAndFlush(schedule).getId());
    }

    private Map<DayOfWeek, List<DailyOperatingHours>> allDays(
            DailyOperatingHours operatingHours
    ) {
        EnumMap<DayOfWeek, List<DailyOperatingHours>> result =
                new EnumMap<>(DayOfWeek.class);
        Arrays.stream(DayOfWeek.values())
                .forEach(day -> result.put(day, List.of(operatingHours)));
        return result;
    }
}
