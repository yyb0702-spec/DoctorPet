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

/** 공공데이터 최초 적재와 주간 갱신의 책임을 분리합니다. */
@Service
@RequiredArgsConstructor
public class AnimalHospitalRefreshService {

    private final AnimalHospitalCollectionService collectionService;
    private final PartnerHospitalSeedLoader partnerHospitalSeedLoader;
    private final PartnerHospitalSeedService partnerHospitalSeedService;
    private final HospitalSearchCacheRepository searchCacheRepository;

    /** 주간 갱신은 공공 원천 기본정보만 갱신하며 제휴 초기값을 다시 적용하지 않습니다. */
    public AnimalHospitalRefreshResult refresh() {
        AnimalHospitalCollectionResult collectionResult =
                collectionService.collectNationwide();
        searchCacheRepository.evictInitialPage();

        return new AnimalHospitalRefreshResult(collectionResult, 0);
    }

    /** 최초 데이터 구축에서만 공공데이터 적재 후 제휴 JSON 초기값을 적용합니다. */
    public AnimalHospitalRefreshResult seed() {
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
