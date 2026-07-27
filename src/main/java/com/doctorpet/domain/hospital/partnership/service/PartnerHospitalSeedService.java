package com.doctorpet.domain.hospital.partnership.service;

import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.entity.HospitalCapability;
import com.doctorpet.domain.hospital.entity.HospitalDetail;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.mapper.PartnerHospitalSeedMapper;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalDetailRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 제휴 더미 데이터와 공공데이터 병원을 복합 키로 매칭합니다.
 */
@Service
@RequiredArgsConstructor
public class PartnerHospitalSeedService {

    private final HospitalRepository hospitalRepository;
    private final HospitalDetailRepository hospitalDetailRepository;
    private final HospitalCapabilityRepository hospitalCapabilityRepository;
    private final PartnerHospitalSeedMapper seedMapper;

    /**
     * 매칭된 병원을 제휴 상태로 전환하고 처리 건수를 반환합니다.
     * 제휴 JSON은 하나의 데이터 세트이므로 한 건이라도 매칭에 실패하면 전체 변경을 취소합니다.
     */
    @Transactional
    public int applyPartnerships(List<PartnerHospitalSeedData> seedDataList) {
        // 일부 병원만 제휴되는 불완전한 상태를 막기 위해 전체 목록을 하나의 트랜잭션으로 처리합니다.
        seedDataList.forEach(this::applyPartnership);
        return seedDataList.size();
    }

    private void applyPartnership(PartnerHospitalSeedData seedData) {
        // 병원명은 중복·변경될 수 있으므로 지자체 코드와 관리번호로 조회합니다.
        Hospital hospital = hospitalRepository
                .findByLocalGovCodeAndMgmtNo(
                        seedData.localGovernmentCode(),
                        seedData.managementNumber()
                )
                .orElseThrow(() -> new IllegalStateException(
                        "제휴 대상 병원을 찾을 수 없습니다: "
                                + seedData.hospitalName()
                ));

        // 트랜잭션 안에서 상태를 바꾸면 JPA 변경 감지가 UPDATE 쿼리를 실행합니다.
        hospital.markAsPartner();

        // 같은 JSON을 다시 실행해도 중복되지 않도록 상세정보와 역량을 각각 맞춥니다.
        synchronizeDetail(hospital, seedData);
        synchronizeCapabilities(hospital, seedData.capabilities());
    }

    private void synchronizeDetail(
            Hospital hospital,
            PartnerHospitalSeedData seedData
    ) {
        // detailSeed는 JSON에서 읽은 상세정보 DTO입니다.
        var detailSeed = seedData.detail();

        // 문자열 시간("09:00")을 LocalTime을 사용하는 도메인 운영시간으로 변환합니다.
        var openHours = seedMapper.toOpenHours(detailSeed);

        // 상세정보가 있으면 변경된 값만 수정하고, 없으면 새로 생성합니다.
        hospitalDetailRepository.findByHospital(hospital)
                .ifPresentOrElse(
                        detail -> detail.update(
                                openHours,
                                detailSeed.surgeryAvailable(),
                                detailSeed.hospitalizationAvailable(),
                                detailSeed.nightCare(),
                                detailSeed.emergency()
                        ),
                        () -> hospitalDetailRepository.save(
                                HospitalDetail.create(
                                        hospital,
                                        openHours,
                                        detailSeed.surgeryAvailable(),
                                        detailSeed.hospitalizationAvailable(),
                                        detailSeed.nightCare(),
                                        detailSeed.emergency()
                                )
                        )
                );
    }

    private void synchronizeCapabilities(
            Hospital hospital,
            List<CapabilityValue> capabilitySeeds
    ) {
        // 현재 DB에 저장된 이 병원의 모든 역량을 가져옵니다.
        List<HospitalCapability> existingCapabilities =
                hospitalCapabilityRepository.findAllByHospital(hospital);

        // JSON에 적힌 역량을 중복 없는 최종 목표 목록으로 만듭니다.
        EnumSet<CapabilityValue> desiredValues =
                EnumSet.noneOf(CapabilityValue.class);
        desiredValues.addAll(capabilitySeeds);

        // DB에는 있지만 JSON에는 없는 역량만 삭제 대상으로 고릅니다.
        List<HospitalCapability> capabilitiesToRemove =
                existingCapabilities.stream()
                        .filter(capability -> !desiredValues.contains(
                                capability.getCapabilityValue()
                        ))
                        .toList();
        if (!capabilitiesToRemove.isEmpty()) {
            hospitalCapabilityRepository.deleteAll(capabilitiesToRemove);
        }

        // 새 역량과 비교하기 쉽도록 기존 엔티티에서 역량 값만 꺼내 Set으로 만듭니다.
        Set<CapabilityValue> existingValues =
                existingCapabilities.stream()
                        .map(HospitalCapability::getCapabilityValue)
                        .collect(java.util.stream.Collectors.toSet());

        // JSON에는 있지만 DB에는 없는 역량만 새 엔티티로 만듭니다.
        List<HospitalCapability> capabilitiesToAdd =
                desiredValues.stream()
                        .filter(value -> !existingValues.contains(value))
                        .map(value -> HospitalCapability.create(
                                hospital,
                                value
                        ))
                        .toList();
        if (!capabilitiesToAdd.isEmpty()) {
            hospitalCapabilityRepository.saveAll(capabilitiesToAdd);
        }
    }
}
