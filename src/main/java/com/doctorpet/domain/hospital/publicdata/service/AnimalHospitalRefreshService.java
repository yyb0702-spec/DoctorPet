package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.infrastructure.PartnerHospitalSeedLoader;
import com.doctorpet.domain.hospital.partnership.service.PartnerHospitalSeedService;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 공공데이터 갱신 후 제휴 보강 데이터를 다시 적용합니다. */
@Service
@RequiredArgsConstructor
public class AnimalHospitalRefreshService {

    private final AnimalHospitalCollectionService collectionService;
    private final PartnerHospitalSeedLoader partnerHospitalSeedLoader;
    private final PartnerHospitalSeedService partnerHospitalSeedService;
    private final HospitalSearchCacheRepository searchCacheRepository;

    public AnimalHospitalRefreshResult refresh() {
        AnimalHospitalCollectionResult collectionResult =
                collectionService.collectNationwide();
        List<PartnerHospitalSeedData> partnerData =
                partnerHospitalSeedLoader.load();
        int appliedPartnerCount =
                partnerHospitalSeedService.applyPartnerships(partnerData);
        searchCacheRepository.evictInitialPage();

        return new AnimalHospitalRefreshResult(collectionResult, appliedPartnerCount);
    }
}
