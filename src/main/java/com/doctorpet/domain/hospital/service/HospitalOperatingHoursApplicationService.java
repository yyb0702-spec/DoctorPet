package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.OperatingHoursResponse;
import com.doctorpet.domain.hospital.entity.HospitalOperatingSchedule;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalOperatingScheduleRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalOperatingHoursApplicationService {

    private final MemberService memberService;
    private final HospitalOperatingScheduleRepository scheduleRepository;
    private final Clock applicationClock;

    public OperatingHoursResponse getOperatingHours(Long memberId) {
        Long hospitalId = getHospitalId(memberId);
        LocalDate today = LocalDate.now(applicationClock);
        HospitalOperatingSchedule schedule = scheduleRepository
                .findEffectiveSchedule(hospitalId, today)
                .orElseThrow(() -> new ServiceException(
                        HospitalErrorCode.OPERATING_SCHEDULE_NOT_FOUND
                ));

        return OperatingHoursResponse.from(schedule);
    }

    private Long getHospitalId(Long memberId) {
        MemberResponse member = memberService.getMyInfo(memberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        return member.hospitalId();
    }
}
