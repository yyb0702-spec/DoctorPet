package com.doctorpet.domain.hospital.publicdata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.infrastructure.PartnerHospitalSeedLoader;
import com.doctorpet.domain.hospital.partnership.service.PartnerHospitalSeedService;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.domain.hospital.service.HospitalSlotGenerationBatchService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalRefreshServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-11T15:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private AnimalHospitalCollectionService collectionService;
    @Mock
    private PartnerHospitalSeedLoader partnerHospitalSeedLoader;
    @Mock
    private PartnerHospitalSeedService partnerHospitalSeedService;
    @Mock
    private HospitalSearchCacheRepository searchCacheRepository;
    @Mock
    private HospitalSlotGenerationBatchService slotGenerationBatchService;

    @Test
    void weeklyRefreshUpdatesOnlyPublicDataAndEvictsCache() {
        AnimalHospitalCollectionResult collection =
                new AnimalHospitalCollectionResult(3, 250);
        given(collectionService.collectNationwide()).willReturn(collection);
        AnimalHospitalRefreshService service = new AnimalHospitalRefreshService(
                collectionService,
                partnerHospitalSeedLoader,
                partnerHospitalSeedService,
                searchCacheRepository,
                slotGenerationBatchService,
                CLOCK
        );

        AnimalHospitalRefreshResult result = service.refresh();

        assertThat(result.collection()).isEqualTo(collection);
        assertThat(result.appliedPartnerCount()).isZero();
        InOrder order = inOrder(
                collectionService,
                searchCacheRepository
        );
        then(collectionService).should(order).collectNationwide();
        then(searchCacheRepository).should(order).evictInitialPage();
        verifyNoInteractions(
                partnerHospitalSeedLoader,
                partnerHospitalSeedService,
                slotGenerationBatchService
        );
    }

    @Test
    void initialSeedAppliesPartnerJsonAfterPublicDataCollection() {
        AnimalHospitalCollectionResult collection =
                new AnimalHospitalCollectionResult(3, 250);
        List<PartnerHospitalSeedData> partnerData = List.of();
        given(collectionService.collectNationwide()).willReturn(collection);
        given(partnerHospitalSeedLoader.load()).willReturn(partnerData);
        given(partnerHospitalSeedService.applyPartnerships(partnerData)).willReturn(2);
        AnimalHospitalRefreshService service = new AnimalHospitalRefreshService(
                collectionService,
                partnerHospitalSeedLoader,
                partnerHospitalSeedService,
                searchCacheRepository,
                slotGenerationBatchService,
                CLOCK
        );

        AnimalHospitalRefreshResult result = service.seed();

        assertThat(result.collection()).isEqualTo(collection);
        assertThat(result.appliedPartnerCount()).isEqualTo(2);
        InOrder order = inOrder(
                collectionService,
                partnerHospitalSeedLoader,
                partnerHospitalSeedService,
                slotGenerationBatchService,
                searchCacheRepository
        );
        then(collectionService).should(order).collectNationwide();
        then(partnerHospitalSeedLoader).should(order).load();
        then(partnerHospitalSeedService).should(order).applyPartnerships(partnerData);
        then(slotGenerationBatchService).should(order).generateRange(
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 25)
        );
        then(searchCacheRepository).should(order).evictInitialPage();
    }
}
