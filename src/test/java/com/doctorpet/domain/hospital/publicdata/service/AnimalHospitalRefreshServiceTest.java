package com.doctorpet.domain.hospital.publicdata.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.infrastructure.PartnerHospitalSeedLoader;
import com.doctorpet.domain.hospital.partnership.service.PartnerHospitalSeedService;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalRefreshServiceTest {

    @Mock
    private AnimalHospitalCollectionService collectionService;
    @Mock
    private PartnerHospitalSeedLoader partnerHospitalSeedLoader;
    @Mock
    private PartnerHospitalSeedService partnerHospitalSeedService;
    @Mock
    private HospitalSearchCacheRepository searchCacheRepository;

    @Test
    void 공공데이터_수집_후_제휴_보강을_다시_적용한다() {
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
                searchCacheRepository
        );

        AnimalHospitalRefreshResult result = service.refresh();

        assertThat(result.collection()).isEqualTo(collection);
        assertThat(result.appliedPartnerCount()).isEqualTo(2);
        InOrder order = inOrder(
                collectionService,
                partnerHospitalSeedLoader,
                partnerHospitalSeedService,
                searchCacheRepository
        );
        then(collectionService).should(order).collectNationwide();
        then(partnerHospitalSeedLoader).should(order).load();
        then(partnerHospitalSeedService).should(order).applyPartnerships(partnerData);
        then(searchCacheRepository).should(order).evictInitialPage();
    }
}
