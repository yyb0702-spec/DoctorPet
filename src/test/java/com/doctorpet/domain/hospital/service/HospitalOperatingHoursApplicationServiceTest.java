package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.dto.request.DailyOperatingHoursRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingHoursUpdateRequest;
import com.doctorpet.domain.hospital.dto.request.OperatingPeriodRequest;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.dto.query.ReservationSlotQueryResult;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
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
    private HospitalOperatingSchedule schedule;

    @InjectMocks
    private HospitalOperatingHoursApplicationService service;

    @BeforeEach
    void setUp() {
        service = new HospitalOperatingHoursApplicationService(
                memberService,
                reservationService,
                hospitalRepository,
                scheduleRepository,
                Clock.fixed(
                        Instant.parse("2026-08-09T15:00:00Z"),
                        TimePolicy.SEOUL_ZONE_ID
                )
        );
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
    void updateOperatingHoursAppliesAfterLatestReservedDate() {
        LocalDate desiredEffectiveFrom = TODAY.plusDays(1);
        LocalDate actualEffectiveFrom = TODAY.plusDays(4);
        OperatingHoursUpdateRequest request = updateRequest(desiredEffectiveFrom);
        Hospital hospital = org.mockito.Mockito.mock(Hospital.class);

        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findSlots(
                HOSPITAL_ID,
                TODAY.atStartOfDay(),
                TODAY.plusDays(14).atStartOfDay()
        )).willReturn(List.of(
                new ReservationSlotQueryResult(
                        100L,
                        TODAY.plusDays(3).atTime(10, 0),
                        TODAY.plusDays(3).atTime(10, 30),
                        ReservationSlotStatus.RESERVED
                )
        ));
        given(scheduleRepository.findSchedule(HOSPITAL_ID, actualEffectiveFrom))
                .willReturn(Optional.empty());
        given(hospitalRepository.findById(HOSPITAL_ID)).willReturn(Optional.of(hospital));
        given(scheduleRepository.save(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateOperatingHours(MEMBER_ID, request);

        assertThat(response.effectiveFrom()).isEqualTo(actualEffectiveFrom);
        assertThat(response.days().get(0).periods()).hasSize(2);
    }

    @Test
    void updateOperatingHoursChangesScheduleWithSameEffectiveDate() {
        LocalDate desiredEffectiveFrom = TODAY.plusDays(1);
        OperatingHoursUpdateRequest request = updateRequest(desiredEffectiveFrom);
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(reservationService.findSlots(
                HOSPITAL_ID,
                TODAY.atStartOfDay(),
                TODAY.plusDays(14).atStartOfDay()
        )).willReturn(List.of());
        given(scheduleRepository.findSchedule(HOSPITAL_ID, desiredEffectiveFrom))
                .willReturn(Optional.of(schedule));
        given(scheduleRepository.save(schedule)).willReturn(schedule);
        given(schedule.getEffectiveFrom()).willReturn(desiredEffectiveFrom);
        given(schedule.getOperatingHours()).willReturn(Map.of());

        service.updateOperatingHours(MEMBER_ID, request);

        verify(schedule).changeOperatingHours(org.mockito.ArgumentMatchers.anyMap());
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
