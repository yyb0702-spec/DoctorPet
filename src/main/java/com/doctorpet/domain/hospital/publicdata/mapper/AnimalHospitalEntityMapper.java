package com.doctorpet.domain.hospital.publicdata.mapper;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.publicdata.model.NormalizedAnimalHospitalData;
import org.springframework.stereotype.Component;

/**
 * 정규화된 공공데이터를 Hospital 엔티티로 변환합니다.
 */
@Component
public class AnimalHospitalEntityMapper {

    /**
     * 병원 식별과 운영에 필요한 필수값을 확인한 후 비제휴 병원을 생성합니다.
     */
    public Hospital toEntity(NormalizedAnimalHospitalData data) {
        validateRequiredFields(data);

        return Hospital.createFromPublicData(
                data.managementNumber(),
                data.localGovernmentCode(),
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
    }

    private void validateRequiredFields(NormalizedAnimalHospitalData data) {
        if (data == null) {
            throw new IllegalArgumentException("정규화된 병원 데이터가 필요합니다.");
        }
        if (data.managementNumber() == null) {
            throw new IllegalArgumentException("병원 관리번호가 필요합니다.");
        }
        if (data.localGovernmentCode() == null) {
            throw new IllegalArgumentException("개방자치단체코드가 필요합니다.");
        }
        if (data.name() == null) {
            throw new IllegalArgumentException("병원명이 필요합니다.");
        }
        if (data.businessStatus() == null) {
            throw new IllegalArgumentException("병원 영업상태가 필요합니다.");
        }
    }
}
