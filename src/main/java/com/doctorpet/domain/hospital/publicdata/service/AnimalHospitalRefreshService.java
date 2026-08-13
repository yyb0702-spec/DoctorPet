package com.doctorpet.domain.hospital.publicdata.service;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.infrastructure.PartnerHospitalSeedLoader;
import com.doctorpet.domain.hospital.partnership.service.PartnerHospitalSeedService;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.domain.hospital.scheduler.HospitalSlotGenerationSummary;
import com.doctorpet.domain.hospital.service.HospitalSlotGenerationBatchService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 공공데이터 최초 적재와 정기 갱신의 책임을 분리합니다. */
@Service
@RequiredArgsConstructor
public class AnimalHospitalRefreshService {

    private static final int PUBLISHED_RANGE_LAST_DAY_OFFSET = 13;

    private final AnimalHospitalCollectionService collectionService;
    private final PartnerHospitalSeedLoader partnerHospitalSeedLoader;
    private final PartnerHospitalSeedService partnerHospitalSeedService;
    private final HospitalSearchCacheRepository searchCacheRepository;
    private final HospitalSlotGenerationBatchService slotGenerationBatchService;
    private final Clock applicationClock;

    /** 정기 갱신은 공공 원천 기본정보만 갱신하며 제휴 초기값을 다시 적용하지 않습니다. */
    public AnimalHospitalRefreshResult refresh() {
        AnimalHospitalCollectionResult collectionResult =
                collectionService.collectNationwide();
        searchCacheRepository.evictInitialPage();

        return new AnimalHospitalRefreshResult(collectionResult, 0);
    }

    /** 최초 데이터 구축에서만 제휴 JSON을 적용하고 공개 범위 14일의 슬롯을 즉시 생성합니다. */
    public AnimalHospitalRefreshResult seed() {
        AnimalHospitalCollectionResult collectionResult =
                collectionService.collectNationwide();
        List<PartnerHospitalSeedData> partnerData =
                partnerHospitalSeedLoader.load();
        int appliedPartnerCount =
                partnerHospitalSeedService.applyPartnerships(partnerData);
        LocalDate today = LocalDate.now(applicationClock);
        HospitalSlotGenerationSummary slotSummary =
                slotGenerationBatchService.generateRange(
                        today,
                        today.plusDays(PUBLISHED_RANGE_LAST_DAY_OFFSET)
                );
        validateSlotGeneration(slotSummary);
        searchCacheRepository.evictInitialPage();

        return new AnimalHospitalRefreshResult(collectionResult, appliedPartnerCount);
    }

    private void validateSlotGeneration(HospitalSlotGenerationSummary summary) {
        if (!summary.locked()) {
            throw new IllegalStateException(
                    "최초 제휴 병원 슬롯 생성 락을 획득하지 못했습니다."
            );
        }
        if (summary.failedTasks() > 0) {
            throw new IllegalStateException(
                    "최초 제휴 병원 슬롯 생성에 실패한 작업이 있습니다. failedTasks="
                            + summary.failedTasks()
            );
        }
    }
}
