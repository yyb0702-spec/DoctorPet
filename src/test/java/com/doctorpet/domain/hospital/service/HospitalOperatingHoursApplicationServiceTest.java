package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.dto.request.DailyOperatingHoursRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingHoursUpdateRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingPeriodRequest;
import com.doctorpet.domain.hospital.dto.request.TemporaryClosureCreateRequest;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.entity.HospitalTemporaryClosure;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.hospital.repository.HospitalTemporaryClosureRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.dto.request.ReservationSlotCreateCommand;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.time.TimePolicy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HospitalOperatingHoursApplicationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long HOSPITAL_ID = 10L;
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    @Mock
    private MemberService memberService;

    @Mock
    private ReservationService reservationService;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private HospitalOperatingScheduleRepository scheduleRepository;

    @Mock
    private HospitalTemporaryClosureRepository closureRepository;

    @Mock
    private HospitalOperatingSchedule schedule;

    @Mock
    private Hospital lockedHospital;

    @InjectMocks
    private HospitalOperatingHoursApplicationService service;

    @BeforeEach
    void setUp() {
        service = new HospitalOperatingHoursApplicationService(
                memberService,
                reservationService,
                hospitalRepository,
                scheduleRepository,
                closureRepository,
                Clock.fixed(
                        Instant.parse("2026-08-09T15:00:00Z"),
                        TimePolicy.SEOUL_ZONE_ID
                )
        );
        lenient().when(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .thenReturn(Optional.of(lockedHospital));
        lenient().when(lockedHospital.getBusinessStatus())
                .thenReturn(BusinessStatus.OPEN);
    }

    @Test
    void getOperatingHoursReturnsEffectiveSchedule() {
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(scheduleRepository.findEffectiveSchedule(HOSPITAL_ID, TODAY))
                .willReturn(Optional.of(schedule));
        given(schedule.getEffectiveFrom()).willReturn(LocalDate.of(2026, 8, 1));
        given(schedule.getOperatingHours()).willReturn(Map.of(
                DayOfWeek.MONDAY,
                List.of(
                        new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new DailyOperatingHours(LocalTime.of(13, 0), LocalTime.of(18, 0))
                )
        ));

        var response = service.getOperatingHours(MEMBER_ID);

        assertThat(response.effectiveFrom()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(response.days()).hasSize(7);
        assertThat(response.days().get(0).periods()).hasSize(2);
        assertThat(response.days().get(1).periods()).isEmpty();
    }

    @Test
    void getOperatingHoursRejectsMemberWithoutHospital() {
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(null));

        assertThatThrownBy(() -> service.getOperatingHours(MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL));
    }

    @Test
    void getScheduledOperatingHoursReturnsAllFutureSchedulesInOrder() {
        HospitalOperatingSchedule firstSchedule = org.mockito.Mockito.mock(
                HospitalOperatingSchedule.class
        );
        HospitalOperatingSchedule secondSchedule = org.mockito.Mockito.mock(
                HospitalOperatingSchedule.class
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(scheduleRepository.findScheduledSchedules(HOSPITAL_ID, TODAY))
                .willReturn(List.of(firstSchedule, secondSchedule));
        given(firstSchedule.getEffectiveFrom()).willReturn(TODAY.plusDays(2));
        given(secondSchedule.getEffectiveFrom()).willReturn(TODAY.plusDays(5));
        given(firstSchedule.getOperatingHours()).willReturn(Map.of());
        given(secondSchedule.getOperatingHours()).willReturn(Map.of());

        var responses = service.getScheduledOperatingHours(MEMBER_ID);

        assertThat(responses)
                .extracting(response -> response.effectiveFrom())
                .containsExactly(TODAY.plusDays(2), TODAY.plusDays(5));
        verify(scheduleRepository).findScheduledSchedules(HOSPITAL_ID, TODAY);
    }

    @Test
    void getScheduledOperatingHoursRejectsMemberWithoutHospital() {
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(null));

        assertThatThrownBy(() -> service.getScheduledOperatingHours(MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL));
    }

    @Test
    void updateOperatingHoursAppliesAfterLatestReservedDate() {
        LocalDate desiredEffectiveFrom = TODAY.plusDays(1);
        LocalDate actualEffectiveFrom = TODAY.plusDays(4);
        OperatingHoursUpdateRequest request = updateRequest(desiredEffectiveFrom);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);

        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findLatestReservedBusinessDate(
                HOSPITAL_ID,
                TODAY,
                TODAY.plusDays(13)
        )).willReturn(Optional.of(TODAY.plusDays(3)));
        given(scheduleRepository.findSchedule(HOSPITAL_ID, actualEffectiveFrom))
                .willReturn(Optional.empty());
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospital.getBusinessStatus()).willReturn(BusinessStatus.OPEN);
        given(scheduleRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateOperatingHours(MEMBER_ID, request);

        assertThat(response.effectiveFrom()).isEqualTo(actualEffectiveFrom);
        assertThat(response.days().get(0).periods()).hasSize(2);
    }

    @Test
    void updateOperatingHoursAppliesAfterLastPublishedBusinessDateReservation() {
        LocalDate desiredEffectiveFrom = TODAY.plusDays(1);
        LocalDate lastPublishedBusinessDate = TODAY.plusDays(13);
        OperatingHoursUpdateRequest request = updateRequest(desiredEffectiveFrom);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);

        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findLatestReservedBusinessDate(
                HOSPITAL_ID,
                TODAY,
                lastPublishedBusinessDate
        )).willReturn(Optional.of(lastPublishedBusinessDate));
        given(scheduleRepository.findSchedule(
                HOSPITAL_ID,
                TODAY.plusDays(14)
        )).willReturn(Optional.empty());
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(scheduleRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateOperatingHours(MEMBER_ID, request);

        assertThat(response.effectiveFrom()).isEqualTo(TODAY.plusDays(14));
        verify(reservationService, never())
                .lockOpenSlotsForReplacement(any(), any(), any());
    }

    @Test
    void updateOperatingHoursChangesScheduleWithSameEffectiveDate() {
        LocalDate desiredEffectiveFrom = TODAY.plusDays(1);
        OperatingHoursUpdateRequest request = updateRequest(desiredEffectiveFrom);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findLatestReservedBusinessDate(
                HOSPITAL_ID,
                TODAY,
                TODAY.plusDays(13)
        )).willReturn(Optional.empty());
        given(scheduleRepository.findSchedule(HOSPITAL_ID, desiredEffectiveFrom))
                .willReturn(Optional.of(schedule));
        given(scheduleRepository.save(schedule)).willReturn(schedule);
        given(schedule.getEffectiveFrom()).willReturn(desiredEffectiveFrom);
        given(schedule.getOperatingHours()).willReturn(Map.of());

        service.updateOperatingHours(MEMBER_ID, request);

        verify(schedule).changeOperatingHours(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void updateOperatingHoursReplacesPublishedSlotsFromEffectiveDate() {
        LocalDate effectiveFrom = TODAY.plusDays(11);
        OperatingHoursUpdateRequest request = updateRequest(effectiveFrom);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findLatestReservedBusinessDate(
                HOSPITAL_ID,
                TODAY,
                TODAY.plusDays(13)
        )).willReturn(Optional.empty());
        given(scheduleRepository.findSchedule(HOSPITAL_ID, effectiveFrom))
                .willReturn(Optional.empty());
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospital.getBusinessStatus()).willReturn(BusinessStatus.OPEN);
        given(scheduleRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(closureRepository.findClosure(any(), any())).willReturn(Optional.empty());

        service.updateOperatingHours(MEMBER_ID, request);

        verify(reservationService).lockOpenSlotsForReplacement(
                HOSPITAL_ID,
                TODAY.plusDays(11),
                TODAY.plusDays(13)
        );
        verify(reservationService).replaceOpenSlots(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(TODAY.plusDays(11)),
                any()
        );
        verify(reservationService).replaceOpenSlots(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(TODAY.plusDays(12)),
                any()
        );
        verify(reservationService).replaceOpenSlots(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(TODAY.plusDays(13)),
                any()
        );
    }

    @Test
    void updateOperatingHoursDoesNotReplaceSlotsForClosedHospital() {
        LocalDate effectiveFrom = TODAY.plusDays(1);
        OperatingHoursUpdateRequest request = updateRequest(effectiveFrom);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findLatestReservedBusinessDate(
                HOSPITAL_ID,
                TODAY,
                TODAY.plusDays(13)
        )).willReturn(Optional.empty());
        given(scheduleRepository.findSchedule(HOSPITAL_ID, effectiveFrom))
                .willReturn(Optional.empty());
        given(lockedHospital.getBusinessStatus())
                .willReturn(BusinessStatus.CLOSED_TEMP);
        given(scheduleRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.updateOperatingHours(MEMBER_ID, request);

        verify(reservationService, never())
                .lockOpenSlotsForReplacement(any(), any(), any());
        verify(reservationService, never())
                .replaceOpenSlots(any(), any(), any());
    }

    @Test
    void updateOperatingHoursDoesNotReplaceSlotsOutsidePublishedRange() {
        LocalDate effectiveFrom = TODAY.plusDays(14);
        OperatingHoursUpdateRequest request = updateRequest(effectiveFrom);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findLatestReservedBusinessDate(
                HOSPITAL_ID,
                TODAY,
                TODAY.plusDays(13)
        )).willReturn(Optional.empty());
        given(scheduleRepository.findSchedule(HOSPITAL_ID, effectiveFrom))
                .willReturn(Optional.empty());
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(scheduleRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.updateOperatingHours(MEMBER_ID, request);

        verify(reservationService, never()).lockOpenSlotsForReplacement(any(), any(), any());
        verify(reservationService, never()).replaceOpenSlots(any(), any(), any());
    }

    @Test
    void updateOperatingHoursRejectsOverlappingPeriods() {
        OperatingHoursUpdateRequest request = new OperatingHoursUpdateRequest(
                TODAY.plusDays(1),
                allDays(List.of(
                        new OperatingPeriodRequest(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                        new OperatingPeriodRequest(LocalTime.of(12, 0), LocalTime.of(18, 0))
                ))
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));

        assertThatThrownBy(() -> service.updateOperatingHours(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(HospitalErrorCode.INVALID_OPERATING_HOURS));
    }

    @Test
    void updateOperatingHoursRejectsOverlapAcrossDayBoundary() {
        OperatingHoursUpdateRequest request = new OperatingHoursUpdateRequest(
                TODAY.plusDays(1),
                java.util.Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyOperatingHoursRequest(
                                day,
                                switch (day) {
                                    case MONDAY -> List.of(new OperatingPeriodRequest(
                                            LocalTime.of(22, 0), LocalTime.of(2, 0)));
                                    case TUESDAY -> List.of(new OperatingPeriodRequest(
                                            LocalTime.of(1, 0), LocalTime.of(3, 0)));
                                    default -> List.of();
                                }
                        ))
                        .toList()
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));

        assertThatThrownBy(() -> service.updateOperatingHours(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(HospitalErrorCode.INVALID_OPERATING_HOURS));
    }

    @Test
    void updateOperatingHoursRejectsOverlapAcrossWeekBoundary() {
        OperatingHoursUpdateRequest request = new OperatingHoursUpdateRequest(
                TODAY.plusDays(1),
                java.util.Arrays.stream(DayOfWeek.values())
                        .map(day -> new DailyOperatingHoursRequest(
                                day,
                                switch (day) {
                                    case SUNDAY -> List.of(new OperatingPeriodRequest(
                                            LocalTime.of(23, 0), LocalTime.of(2, 0)));
                                    case MONDAY -> List.of(new OperatingPeriodRequest(
                                            LocalTime.of(1, 0), LocalTime.of(3, 0)));
                                    default -> List.of();
                                }
                        ))
                        .toList()
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));

        assertThatThrownBy(() -> service.updateOperatingHours(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(HospitalErrorCode.INVALID_OPERATING_HOURS));
    }

    @Test
    void createTemporaryClosureStoresClosureAfterRemovingOpenSlots() {
        LocalDate businessDate = TODAY.plusDays(5);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.empty());
        given(reservationService.removeOpenSlotsIfNoReservation(
                HOSPITAL_ID,
                businessDate
        )).willReturn(true);
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(closureRepository.save(any(HospitalTemporaryClosure.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = service.createTemporaryClosure(
                MEMBER_ID,
                new TemporaryClosureCreateRequest(businessDate)
        );

        assertThat(response.businessDate()).isEqualTo(businessDate);
        verify(closureRepository).save(any(HospitalTemporaryClosure.class));
    }

    @Test
    void createTemporaryClosureRejectsToday() {
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));

        assertThatThrownBy(() -> service.createTemporaryClosure(
                MEMBER_ID,
                new TemporaryClosureCreateRequest(TODAY)
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.INVALID_TEMPORARY_CLOSURE_DATE);

        verify(reservationService, never())
                .removeOpenSlotsIfNoReservation(any(), any());
    }

    @Test
    void createTemporaryClosureRejectsBusinessDateWithReservation() {
        LocalDate businessDate = TODAY.plusDays(1);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.empty());
        given(reservationService.removeOpenSlotsIfNoReservation(
                HOSPITAL_ID,
                businessDate
        )).willReturn(false);

        assertThatThrownBy(() -> service.createTemporaryClosure(
                MEMBER_ID,
                new TemporaryClosureCreateRequest(businessDate)
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.TEMPORARY_CLOSURE_HAS_RESERVATION);

        verify(closureRepository, never()).save(any());
    }

    @Test
    void createTemporaryClosureRejectsDuplicateClosure() {
        LocalDate businessDate = TODAY.plusDays(1);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(
                        HospitalTemporaryClosure.create(hospital, businessDate)
                ));

        assertThatThrownBy(() -> service.createTemporaryClosure(
                MEMBER_ID,
                new TemporaryClosureCreateRequest(businessDate)
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.TEMPORARY_CLOSURE_ALREADY_EXISTS);

        verify(reservationService, never())
                .removeOpenSlotsIfNoReservation(any(), any());
    }

    @Test
    void cancelTemporaryClosureRestoresSlotsInsidePublishedRange() {
        LocalDate businessDate = TODAY.plusDays(5);
        HospitalTemporaryClosure closure = HospitalTemporaryClosure.create(
                lockedHospital,
                businessDate
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(closure), Optional.empty());
        given(scheduleRepository.findEffectiveSchedule(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(schedule));
        given(schedule.getOperatingHours()).willReturn(Map.of(
                businessDate.getDayOfWeek(),
                List.of(new DailyOperatingHours(
                        LocalTime.of(9, 0),
                        LocalTime.of(10, 0)
                ))
        ));

        service.cancelTemporaryClosure(MEMBER_ID, businessDate);

        verify(closureRepository).delete(closure);
        verify(closureRepository).flush();
        verify(reservationService).createOpenSlots(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(businessDate),
                any()
        );
    }

    @Test
    void cancelTemporaryClosureOutsidePublishedRangeOnlyDeletesClosure() {
        LocalDate businessDate = TODAY.plusDays(14);
        HospitalTemporaryClosure closure = HospitalTemporaryClosure.create(
                lockedHospital,
                businessDate
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(closure));

        service.cancelTemporaryClosure(MEMBER_ID, businessDate);

        verify(closureRepository).delete(closure);
        verify(closureRepository).flush();
        verify(scheduleRepository, never()).findEffectiveSchedule(any(), any());
        verify(reservationService, never()).createOpenSlots(any(), any(), any());
    }

    @Test
    void cancelTemporaryClosureDoesNotCreateSlotsForClosedHospital() {
        LocalDate businessDate = TODAY.plusDays(1);
        HospitalTemporaryClosure closure = HospitalTemporaryClosure.create(
                lockedHospital,
                businessDate
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(lockedHospital.getBusinessStatus())
                .willReturn(BusinessStatus.CLOSED);
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(closure));

        service.cancelTemporaryClosure(MEMBER_ID, businessDate);

        verify(closureRepository).delete(closure);
        verify(reservationService, never())
                .createOpenSlots(any(), any(), any());
    }

    @Test
    void cancelTemporaryClosureRejectsBusinessDateStartingToday() {
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));

        assertThatThrownBy(() -> service.cancelTemporaryClosure(MEMBER_ID, TODAY))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(
                                HospitalErrorCode
                                        .TEMPORARY_CLOSURE_CANCEL_DEADLINE_PASSED
                        ));

        verify(hospitalRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void cancelTemporaryClosureRejectsMissingClosure() {
        LocalDate businessDate = TODAY.plusDays(1);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelTemporaryClosure(
                MEMBER_ID,
                businessDate
        )).isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(HospitalErrorCode.TEMPORARY_CLOSURE_NOT_FOUND));
    }

    @Test
    void createSlotsCreatesThirtyMinuteSlotsAndDropsRemainder() {
        LocalDate businessDate = LocalDate.of(2026, 8, 10);
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.empty());
        given(scheduleRepository.findEffectiveSchedule(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(schedule));
        given(schedule.getOperatingHours()).willReturn(Map.of(
                businessDate.getDayOfWeek(),
                List.of(new DailyOperatingHours(
                        LocalTime.of(9, 0),
                        LocalTime.of(10, 10)
                ))
        ));
        given(reservationService.createOpenSlots(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(businessDate),
                any()
        )).willReturn(2);

        int created = service.createSlots(HOSPITAL_ID, businessDate);

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<List<ReservationSlotCreateCommand>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(reservationService).createOpenSlots(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(businessDate),
                commands.capture()
        );
        assertThat(commands.getValue())
                .extracting(
                        ReservationSlotCreateCommand::startAt,
                        ReservationSlotCreateCommand::endAt
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                businessDate.atTime(9, 0),
                                businessDate.atTime(9, 30)
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                businessDate.atTime(9, 30),
                                businessDate.atTime(10, 0)
                        )
                );
    }

    @Test
    void createSlotsKeepsOvernightSlotsOnBusinessDate() {
        LocalDate businessDate = LocalDate.of(2026, 8, 10);
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.empty());
        given(scheduleRepository.findEffectiveSchedule(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(schedule));
        given(schedule.getOperatingHours()).willReturn(Map.of(
                businessDate.getDayOfWeek(),
                List.of(new DailyOperatingHours(
                        LocalTime.of(23, 30),
                        LocalTime.of(1, 0)
                ))
        ));

        service.createSlots(HOSPITAL_ID, businessDate);

        ArgumentCaptor<List<ReservationSlotCreateCommand>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(reservationService).createOpenSlots(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(businessDate),
                commands.capture()
        );
        assertThat(commands.getValue())
                .extracting(ReservationSlotCreateCommand::startAt)
                .containsExactly(
                        businessDate.atTime(23, 30),
                        businessDate.plusDays(1).atStartOfDay(),
                        businessDate.plusDays(1).atTime(0, 30)
                );
    }

    @Test
    void createSlotsSkipsTemporaryClosure() {
        LocalDate businessDate = LocalDate.of(2026, 8, 10);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);
        given(closureRepository.findClosure(HOSPITAL_ID, businessDate))
                .willReturn(Optional.of(
                        HospitalTemporaryClosure.create(hospital, businessDate)
                ));

        assertThat(service.createSlots(HOSPITAL_ID, businessDate)).isZero();

        verify(scheduleRepository, never())
                .findEffectiveSchedule(any(), any());
        verify(reservationService, never())
                .createOpenSlots(any(), any(), any());
    }

    @Test
    void createSlotsSkipsClosedHospital() {
        given(lockedHospital.getBusinessStatus()).willReturn(BusinessStatus.CLOSED);

        assertThat(service.createSlots(HOSPITAL_ID, TODAY.plusDays(1))).isZero();

        verify(closureRepository, never()).findClosure(any(), any());
        verify(scheduleRepository, never()).findEffectiveSchedule(any(), any());
        verify(reservationService, never()).createOpenSlots(any(), any(), any());
    }

    private OperatingHoursUpdateRequest updateRequest(LocalDate desiredEffectiveFrom) {
        return new OperatingHoursUpdateRequest(
                desiredEffectiveFrom,
                allDays(List.of(
                        new OperatingPeriodRequest(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                        new OperatingPeriodRequest(LocalTime.of(13, 0), LocalTime.of(18, 0))
                ))
        );
    }

    private List<DailyOperatingHoursRequest> allDays(List<OperatingPeriodRequest> monday) {
        return java.util.Arrays.stream(DayOfWeek.values())
                .map(day -> new DailyOperatingHoursRequest(
                        day,
                        day == DayOfWeek.MONDAY ? monday : List.of()
                ))
                .toList();
    }

    private MemberResponse staff(Long hospitalId) {
        return new MemberResponse(
                MEMBER_ID,
                "staff@example.com",
                "staff",
                "010-0000-0000",
                MemberRole.HOSPITAL_STAFF,
                hospitalId
        );
    }
}
