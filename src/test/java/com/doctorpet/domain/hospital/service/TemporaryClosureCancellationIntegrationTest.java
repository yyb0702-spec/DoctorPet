package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.hospital.dto.request.DailyOperatingHoursRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingHoursSaveMode;
import com.doctorpet.domain.hospital.dto.request.OperatingHoursUpdateRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingPeriodRequest;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalTemporaryClosureRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.time.TimePolicy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

// applicationClock을 @MockitoBean으로 갈아끼운 유일한 클래스다(테스트 스위트 전체에서 Clock을 목으로
// 바꾸는 곳이 여기뿐임을 확인했다). CI 잡 분리(unitTest/integrationTest) 이후 실제 CI에서
// EmailVerifiedBackfillRunnerIntegrationTest가 이 클래스의 목 Clock을 물려받은 것으로 보이는
// NullPointerException(Clock.getZone()==null, Mockito 미스터빙 기본값)이 재현됐다 — 정확한
// Spring TestContext 캐시 충돌 경로까지는 특정하지 못했지만, 이 컨텍스트를 캐시에 남겨 재사용시키지
// 않는 것이 가장 안전한 방어다. @DirtiesContext(AFTER_CLASS)로 이 클래스가 끝나면 컨텍스트를 항상
// 폐기해, 목 Clock이 이후 어떤 테스트에도 재사용될 가능성 자체를 없앤다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "ai.openai.api-key=test-key",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class TemporaryClosureCancellationIntegrationTest {

    private static final Long MEMBER_ID = 10L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 11);

    @Autowired
    private HospitalOperatingHoursApplicationService service;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private HospitalOperatingScheduleRepository scheduleRepository;

    @Autowired
    private HospitalTemporaryClosureRepository closureRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private Clock applicationClock;

    private Long hospitalId;
    private Long scheduleId;
    private Long generatedScheduleId;
    private Long closureId;

    @BeforeEach
    void setUp() {
        given(applicationClock.instant()).willReturn(
                Instant.parse("2026-08-10T15:00:00Z")
        );
        given(applicationClock.getZone()).willReturn(TimePolicy.SEOUL_ZONE_ID);

        Hospital hospital = hospitalRepository.saveAndFlush(createHospital());
        hospitalId = hospital.getId();
        HospitalOperatingSchedule schedule = scheduleRepository.saveAndFlush(
                HospitalOperatingSchedule.create(
                        hospital,
                        TODAY,
                        Map.of(
                                TODAY.plusDays(4).getDayOfWeek(),
                                List.of(new DailyOperatingHours(
                                        LocalTime.of(9, 0),
                                        LocalTime.of(10, 0)
                                ))
                        )
                )
        );
        scheduleId = schedule.getId();
        HospitalTemporaryClosure closure = closureRepository.saveAndFlush(
                HospitalTemporaryClosure.create(hospital, TODAY.plusDays(4))
        );
        closureId = closure.getId();

        given(memberService.getMyInfo(MEMBER_ID)).willReturn(new MemberResponse(
                MEMBER_ID,
                "staff@example.com",
                "staff",
                "010-0000-0000",
                MemberRole.HOSPITAL_STAFF,
                hospitalId
        ));
    }

    @AfterEach
    void cleanUp() {
        if (hospitalId != null) {
            List<ReservationSlot> slots = slotRepository.findSlotsInRange(
                    hospitalId,
                    TODAY.atStartOfDay(),
                    TODAY.plusDays(14).atStartOfDay()
            );
            slotRepository.deleteAll(slots);
            slotRepository.flush();
        }
        if (closureId != null) {
            closureRepository.deleteById(closureId);
            closureRepository.flush();
        }
        if (generatedScheduleId != null) {
            scheduleRepository.deleteById(generatedScheduleId);
            scheduleRepository.flush();
        }
        if (scheduleId != null) {
            scheduleRepository.deleteById(scheduleId);
            scheduleRepository.flush();
        }
        if (hospitalId != null) {
            hospitalRepository.deleteById(hospitalId);
            hospitalRepository.flush();
        }
    }

    @Test
    void cancellationWaitsForHospitalLockAndRestoresPublishedSlots() throws Exception {
        LocalDate businessDate = TODAY.plusDays(4);
        CountDownLatch hospitalLocked = new CountDownLatch(1);
        CountDownLatch releaseHospital = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> lockFuture = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        hospitalRepository.findByIdForUpdate(hospitalId)
                                .orElseThrow();
                        hospitalLocked.countDown();
                        await(releaseHospital);
                    })
            );
            assertThat(hospitalLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> cancellationFuture = executor.submit(() ->
                    service.cancelTemporaryClosure(MEMBER_ID, businessDate)
            );
            assertThatThrownBy(() -> cancellationFuture.get(
                    300,
                    TimeUnit.MILLISECONDS
            )).isInstanceOf(TimeoutException.class);

            releaseHospital.countDown();
            lockFuture.get(30, TimeUnit.SECONDS);
            cancellationFuture.get(30, TimeUnit.SECONDS);

            assertThat(closureRepository.findClosure(hospitalId, businessDate))
                    .isEmpty();
            assertThat(slotRepository.findBusinessDateSlots(hospitalId, businessDate))
                    .extracting(ReservationSlot::getStartAt)
                    .containsExactly(
                            businessDate.atTime(9, 0),
                            businessDate.atTime(9, 30)
                    );
        } finally {
            releaseHospital.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = BusinessStatus.class,
            names = {"CLOSED_TEMP", "CLOSED"}
    )
    void inactiveHospitalCancellationDoesNotRestoreSlots(
            BusinessStatus businessStatus
    ) {
        changeBusinessStatus(businessStatus);
        LocalDate businessDate = TODAY.plusDays(4);

        service.cancelTemporaryClosure(MEMBER_ID, businessDate);

        assertThat(closureRepository.findClosure(hospitalId, businessDate))
                .isEmpty();
        assertThat(slotRepository.findBusinessDateSlots(hospitalId, businessDate))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = BusinessStatus.class,
            names = {"CLOSED_TEMP", "CLOSED"}
    )
    void inactiveHospitalOperatingHoursUpdateDoesNotCreateSlots(
            BusinessStatus businessStatus
    ) {
        changeBusinessStatus(businessStatus);
        LocalDate effectiveFrom = TODAY.plusDays(1);
        OperatingHoursUpdateRequest request = new OperatingHoursUpdateRequest(
                effectiveFrom,
                OperatingHoursSaveMode.CREATE,
                null,
                null,
                java.util.Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyOperatingHoursRequest(
                                day,
                                day == effectiveFrom.getDayOfWeek()
                                        ? List.of(new OperatingPeriodRequest(
                                                LocalTime.of(9, 0),
                                                LocalTime.of(10, 0)
                                        ))
                                        : List.of()
                        ))
                        .toList()
        );

        service.updateOperatingHours(MEMBER_ID, request);
        generatedScheduleId = scheduleRepository.findSchedule(
                hospitalId,
                effectiveFrom
        ).orElseThrow().getId();

        assertThat(slotRepository.findSlotsInRange(
                hospitalId,
                TODAY.atStartOfDay(),
                TODAY.plusDays(14).atStartOfDay()
        )).isEmpty();
    }

    @Test
    void concurrentCreatesForSameEffectiveDateAllowOnlyOneRequest() throws Exception {
        LocalDate effectiveFrom = TODAY.plusDays(2);
        OperatingHoursUpdateRequest request = createOperatingHoursRequest(effectiveFrom);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<HospitalErrorCode> firstFuture = executor.submit(
                    () -> updateAfterStartSignal(request, ready, start)
            );
            Future<HospitalErrorCode> secondFuture = executor.submit(
                    () -> updateAfterStartSignal(request, ready, start)
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(java.util.Arrays.asList(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(
                    null,
                    HospitalErrorCode.OPERATING_SCHEDULE_CONFLICT
            );
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        generatedScheduleId = scheduleRepository.findSchedule(hospitalId, effectiveFrom)
                .orElseThrow()
                .getId();
    }

    private HospitalErrorCode updateAfterStartSignal(
            OperatingHoursUpdateRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        try {
            service.updateOperatingHours(MEMBER_ID, request);
            return null;
        } catch (ServiceException exception) {
            return (HospitalErrorCode) exception.getErrorCode();
        }
    }

    private OperatingHoursUpdateRequest createOperatingHoursRequest(LocalDate effectiveFrom) {
        return new OperatingHoursUpdateRequest(
                effectiveFrom,
                OperatingHoursSaveMode.CREATE,
                null,
                null,
                java.util.Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyOperatingHoursRequest(
                                day,
                                day == effectiveFrom.getDayOfWeek()
                                        ? List.of(new OperatingPeriodRequest(
                                                LocalTime.of(9, 0),
                                                LocalTime.of(10, 0)
                                        ))
                                        : List.of()
                        ))
                        .toList()
        );
    }

    private Hospital createHospital() {
        Hospital hospital = Hospital.createFromPublicData(
                "CLOSURE-CANCEL-" + System.nanoTime(),
                "CLOSURE-CANCEL-GOV",
                "임시 휴무 취소 테스트 병원",
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
        return hospital;
    }

    private void changeBusinessStatus(BusinessStatus businessStatus) {
        Hospital hospital = hospitalRepository.findById(hospitalId).orElseThrow();
        ReflectionTestUtils.setField(
                hospital,
                "businessStatus",
                businessStatus
        );
        hospitalRepository.saveAndFlush(hospital);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("잠금 해제 대기 중 중단되었습니다.", exception);
        }
    }
}
