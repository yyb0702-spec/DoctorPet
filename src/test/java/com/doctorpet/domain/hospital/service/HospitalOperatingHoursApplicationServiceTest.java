package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.model.DailyOperatingHours;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
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
    private HospitalOperatingScheduleRepository scheduleRepository;

    @Mock
    private HospitalOperatingSchedule schedule;

    @InjectMocks
    private HospitalOperatingHoursApplicationService service;

    @BeforeEach
    void setUp() {
        service = new HospitalOperatingHoursApplicationService(
                memberService,
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
