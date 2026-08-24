package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCondition;
import com.doctorpet.domain.hospital.model.CapabilityMatchMode;

import java.math.BigDecimal;
import java.util.List;

public interface HospitalRepositoryCustom {

    List<HospitalSearchCandidate> searchAll(
            HospitalSearchCondition condition
    );

    List<HospitalSearchCandidate> searchAll(
            HospitalSearchCondition condition,
            CapabilityMatchMode capabilityMatchMode
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

    List<HospitalSearchCandidate> searchDistancePage(
            HospitalSearchCondition condition,
            BigDecimal latitude,
            BigDecimal longitude,
            long offset,
            int limit
    );

    long count(HospitalSearchCondition condition);
}
