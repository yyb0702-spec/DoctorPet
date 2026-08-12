package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HospitalCapabilityApplicationServiceTest {

    private static final Long MEMBER_ID = 10L;
    private static final Long HOSPITAL_ID = 20L;

    @Mock
    private MemberService memberService;
    @Mock
    private HospitalCapabilityRepository hospitalCapabilityRepository;
    @Mock
    private HospitalCapability dogCapability;
    @Mock
    private HospitalCapability xrayCapability;

    @Test
    void getCapabilitiesReturnsOwnHospitalCapabilitiesInStableOrder() {
        HospitalCapabilityApplicationService service = service();
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(hospitalCapabilityRepository.findAllByHospitalId(HOSPITAL_ID))
                .willReturn(List.of(xrayCapability, dogCapability));
        given(xrayCapability.getCapabilityValue()).willReturn(CapabilityValue.XRAY);
        given(dogCapability.getCapabilityValue()).willReturn(CapabilityValue.DOG);

        var response = service.getCapabilities(MEMBER_ID);

        assertThat(response.capabilities())
                .containsExactly(CapabilityValue.DOG, CapabilityValue.XRAY);
    }

    @Test
    void getCapabilitiesReturnsEmptyListWhenHospitalHasNoCapabilities() {
        HospitalCapabilityApplicationService service = service();
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(hospitalCapabilityRepository.findAllByHospitalId(HOSPITAL_ID))
                .willReturn(List.of());

        var response = service.getCapabilities(MEMBER_ID);

        assertThat(response.capabilities()).isEmpty();
    }

    @Test
    void getCapabilitiesRejectsStaffWithoutHospital() {
        HospitalCapabilityApplicationService service = service();
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(null));

        assertThatThrownBy(() -> service.getCapabilities(MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL));
    }

    private HospitalCapabilityApplicationService service() {
        return new HospitalCapabilityApplicationService(
                memberService,
                hospitalCapabilityRepository
        );
    }

    private MemberResponse staff(Long hospitalId) {
        return new MemberResponse(
                MEMBER_ID,
                "staff@example.com",
                "staff",
                "010-1234-5678",
                MemberRole.HOSPITAL_STAFF,
                hospitalId
        );
    }
}
