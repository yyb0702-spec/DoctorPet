package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;

import java.util.List;

public interface HospitalRepositoryCustom {

    List<HospitalSearchCandidate> searchAll(
            HospitalSearchCondition condition
    );

    List<HospitalSearchCandidate> searchPage(
            HospitalSearchCondition condition,
            long offset,
            int limit
    );

    List<HospitalSearchCandidate> searchPartnerFirstPage(
            HospitalSearchCondition condition,
            long offset,
            int limit
    );

    long count(HospitalSearchCondition condition);
}
