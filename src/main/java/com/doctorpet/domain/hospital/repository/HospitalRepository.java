package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 병원 엔티티의 저장과 조회를 담당합니다.
 */
public interface HospitalRepository
        extends JpaRepository<Hospital, Long>, HospitalRepositoryCustom {

    /**
     * 공공데이터와 제휴 데이터를 연결하는 지자체 코드·관리번호 복합 키로 병원을 조회합니다.
     */
    Optional<Hospital> findByLocalGovCodeAndMgmtNo(
            String localGovCode,
            String mgmtNo
    );
}
