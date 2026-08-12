package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.request.HospitalCapabilitiesUpdateRequest;
import com.doctorpet.domain.hospital.dto.response.HospitalCapabilitiesResponse;
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
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalCapabilityApplicationService {

    private final MemberService memberService;
    private final HospitalRepository hospitalRepository;
    private final HospitalCapabilityRepository hospitalCapabilityRepository;

    public HospitalCapabilitiesResponse getCapabilities(Long memberId) {
        Long hospitalId = getHospitalId(memberId);

        return HospitalCapabilitiesResponse.from(
                hospitalCapabilityRepository.findAllByHospitalId(hospitalId)
        );
    }

    @Transactional
    public HospitalCapabilitiesResponse updateCapabilities(
            Long memberId,
            HospitalCapabilitiesUpdateRequest request
    ) {
        Long hospitalId = getHospitalId(memberId);
        List<CapabilityValue> requestedCapabilities = request.capabilities();
        validateNoDuplicates(requestedCapabilities);

        Hospital hospital = hospitalRepository.findByIdForUpdate(hospitalId)
                .orElseThrow(() -> new ServiceException(
                        HospitalErrorCode.HOSPITAL_NOT_FOUND
                ));
        if (hospital.getBusinessStatus() == BusinessStatus.CLOSED) {
            throw new ServiceException(
                    HospitalErrorCode.CLOSED_HOSPITAL_CAPABILITY_UPDATE_NOT_ALLOWED
            );
        }

        hospitalCapabilityRepository.deleteAllByHospitalId(hospitalId);
        List<HospitalCapability> replacements = requestedCapabilities.stream()
                .map(capability -> HospitalCapability.create(hospital, capability))
                .toList();
        List<HospitalCapability> saved = hospitalCapabilityRepository.saveAll(replacements);

        return HospitalCapabilitiesResponse.from(saved);
    }

    private void validateNoDuplicates(List<CapabilityValue> capabilities) {
        if (new HashSet<>(capabilities).size() != capabilities.size()) {
            throw new ServiceException(HospitalErrorCode.DUPLICATE_CAPABILITY);
        }
    }

    private Long getHospitalId(Long memberId) {
        MemberResponse member = memberService.getMyInfo(memberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        return member.hospitalId();
    }
}
