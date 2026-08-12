package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.hospital.dto.request.HospitalCapabilitiesUpdateRequest;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import java.util.Optional;
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
    private HospitalRepository hospitalRepository;
    @Mock
    private HospitalCapabilityRepository hospitalCapabilityRepository;
    @Mock
    private Hospital hospital;
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

    @Test
    void updateCapabilitiesReplacesOwnHospitalCapabilities() {
        HospitalCapabilityApplicationService service = service();
        HospitalCapabilitiesUpdateRequest request = new HospitalCapabilitiesUpdateRequest(
                List.of(CapabilityValue.XRAY, CapabilityValue.DOG)
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospital.getBusinessStatus()).willReturn(BusinessStatus.OPEN);
        given(hospitalCapabilityRepository.saveAll(org.mockito.ArgumentMatchers.anyList()))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateCapabilities(MEMBER_ID, request);

        assertThat(response.capabilities())
                .containsExactly(CapabilityValue.DOG, CapabilityValue.XRAY);
        verify(hospitalCapabilityRepository).deleteAllByHospitalId(HOSPITAL_ID);
    }

    @Test
    void updateCapabilitiesAllowsEmptyList() {
        HospitalCapabilityApplicationService service = service();
        HospitalCapabilitiesUpdateRequest request = new HospitalCapabilitiesUpdateRequest(List.of());
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospital.getBusinessStatus()).willReturn(BusinessStatus.OPEN);
        given(hospitalCapabilityRepository.saveAll(List.of())).willReturn(List.of());

        var response = service.updateCapabilities(MEMBER_ID, request);

        assertThat(response.capabilities()).isEmpty();
        verify(hospitalCapabilityRepository).deleteAllByHospitalId(HOSPITAL_ID);
    }

    @Test
    void updateCapabilitiesRejectsDuplicateValuesBeforeWriting() {
        HospitalCapabilityApplicationService service = service();
        HospitalCapabilitiesUpdateRequest request = new HospitalCapabilitiesUpdateRequest(
                List.of(CapabilityValue.DOG, CapabilityValue.DOG)
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));

        assertThatThrownBy(() -> service.updateCapabilities(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(HospitalErrorCode.DUPLICATE_CAPABILITY));
        verifyNoInteractions(
                hospitalRepository,
                hospitalCapabilityRepository
        );
    }

    @Test
    void updateCapabilitiesRejectsClosedHospitalWithoutDeletingExistingValues() {
        HospitalCapabilityApplicationService service = service();
        HospitalCapabilitiesUpdateRequest request = new HospitalCapabilitiesUpdateRequest(
                List.of(CapabilityValue.DOG)
        );
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(staff(HOSPITAL_ID));
        given(hospitalRepository.findByIdForUpdate(HOSPITAL_ID))
                .willReturn(Optional.of(hospital));
        given(hospital.getBusinessStatus()).willReturn(BusinessStatus.CLOSED);

        assertThatThrownBy(() -> service.updateCapabilities(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getErrorCode()
                ).isEqualTo(
                        HospitalErrorCode.CLOSED_HOSPITAL_CAPABILITY_UPDATE_NOT_ALLOWED
                ));
        verifyNoInteractions(hospitalCapabilityRepository);
    }

    private HospitalCapabilityApplicationService service() {
        return new HospitalCapabilityApplicationService(
                memberService,
                hospitalRepository,
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
