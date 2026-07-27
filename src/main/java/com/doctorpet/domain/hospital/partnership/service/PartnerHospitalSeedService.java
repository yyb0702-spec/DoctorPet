package com.doctorpet.domain.hospital.partnership.service;

import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 제휴 더미 데이터와 공공데이터 병원을 복합 키로 매칭합니다.
 */
@Service
@RequiredArgsConstructor
public class PartnerHospitalSeedService {

    private final HospitalRepository hospitalRepository;

    /**
     * 매칭된 병원을 제휴 상태로 전환하고 처리 건수를 반환합니다.
     */
    @Transactional
    public int applyPartnerships(List<PartnerHospitalSeedData> seedDataList) {
        // JSON에서 읽은 제휴 병원을 한 건씩 기존 공공데이터 병원과 매칭합니다.
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
    }
}
