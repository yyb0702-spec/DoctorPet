package com.doctorpet.domain.hospital.publicdata.runner;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.infrastructure.PartnerHospitalSeedLoader;
import com.doctorpet.domain.hospital.partnership.service.PartnerHospitalSeedService;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 명시적으로 활성화된 경우 애플리케이션 시작 시 동물병원 공공데이터를 한 번 적재합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "public-data.animal-hospital",
        name = "seed-enabled",
        havingValue = "true"
)
public class AnimalHospitalSeedRunner implements ApplicationRunner {

    private final AnimalHospitalCollectionService collectionService;
    private final PartnerHospitalSeedLoader partnerHospitalSeedLoader;
    private final PartnerHospitalSeedService partnerHospitalSeedService;

    @Override
    public void run(ApplicationArguments args) {
        // 제휴 매칭 대상이 먼저 존재하도록 공공데이터 병원을 우선 적재합니다.
        AnimalHospitalCollectionResult result =
                collectionService.collectConfiguredRegions();

        // 운영자가 제휴 승인을 완료했다고 가정한 JSON 데이터를 공공데이터 병원과 매칭합니다.
        List<PartnerHospitalSeedData> partnerSeedData =
                partnerHospitalSeedLoader.load();
        int appliedPartnerCount =
                partnerHospitalSeedService.applyPartnerships(partnerSeedData);

        log.info(
                "동물병원 시드 적재 완료: 지역 {}개, 페이지 {}개, 병원 {}건, 제휴 {}건",
                result.collectedRegionCount(),
                result.importedPageCount(),
                result.importedHospitalCount(),
                appliedPartnerCount
        );
    }
}
