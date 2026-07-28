package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.publicdata.dto.response.AnimalHospitalItem;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalDataNormalizer;
import com.doctorpet.domain.hospital.publicdata.mapper.AnimalHospitalEntityMapper;
import com.doctorpet.domain.hospital.publicdata.model.NormalizedAnimalHospitalData;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공공데이터 병원 한 건을 신규 저장하거나 기존 병원 정보로 갱신합니다.
 */
@Service
@RequiredArgsConstructor
public class AnimalHospitalImportService {

    private final HospitalRepository hospitalRepository;
    private final AnimalHospitalDataNormalizer normalizer;
    private final AnimalHospitalEntityMapper entityMapper;

    /**
     * 지자체 코드·관리번호 복합 키를 기준으로 병원 정보를 저장하거나 갱신합니다.
     */
    @Transactional
    public Hospital importHospital(AnimalHospitalItem item) {
        NormalizedAnimalHospitalData data = normalizer.normalize(item);

        return hospitalRepository.findByLocalGovCodeAndMgmtNo(
                        data.localGovernmentCode(),
                        data.managementNumber()
                )
                .map(hospital -> updateHospital(hospital, data))
                .orElseGet(() -> hospitalRepository.save(
                        entityMapper.toEntity(data)
                ));
    }

    private Hospital updateHospital(
            Hospital hospital,
            NormalizedAnimalHospitalData data
    ) {
        hospital.updateFromPublicData(
                data.name(),
                data.phone(),
                data.jibunAddress(),
                data.roadAddress(),
                data.zipcode(),
                data.coordinateX(),
                data.coordinateY(),
                data.licenseDate(),
                data.businessStatus(),
                data.closureDate(),
                data.area(),
                data.sourceModifiedAt()
        );
        return hospital;
    }
}
