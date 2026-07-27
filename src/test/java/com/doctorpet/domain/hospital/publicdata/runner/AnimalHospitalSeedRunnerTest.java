package com.doctorpet.domain.hospital.publicdata.runner;

import com.doctorpet.domain.hospital.partnership.dto.PartnerHospitalSeedData;
import com.doctorpet.domain.hospital.partnership.infrastructure.PartnerHospitalSeedLoader;
import com.doctorpet.domain.hospital.partnership.service.PartnerHospitalSeedService;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalCollectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalSeedRunnerTest {

    @Mock
    private AnimalHospitalCollectionService collectionService;

    @Mock
    private PartnerHospitalSeedLoader partnerHospitalSeedLoader;

    @Mock
    private PartnerHospitalSeedService partnerHospitalSeedService;

    @Test
    void 실행되면_공공데이터_적재_후_제휴_데이터를_적용한다() {
        given(collectionService.collectConfiguredRegions())
                .willReturn(new AnimalHospitalCollectionResult(1, 2, 150));
        List<PartnerHospitalSeedData> partnerSeedData = List.of(
                new PartnerHospitalSeedData(
                        "3130000",
                        "313000001020260004",
                        "시그널 동물의료센터"
                )
        );
        given(partnerHospitalSeedLoader.load())
                .willReturn(partnerSeedData);
        given(partnerHospitalSeedService.applyPartnerships(partnerSeedData))
                .willReturn(1);
        AnimalHospitalSeedRunner runner =
                new AnimalHospitalSeedRunner(
                        collectionService,
                        partnerHospitalSeedLoader,
                        partnerHospitalSeedService
                );

        // 제휴 대상 병원이 존재하도록 공공데이터 수집이 항상 먼저 실행되는지 확인합니다.
        runner.run(null);

        org.mockito.InOrder executionOrder = inOrder(
                collectionService,
                partnerHospitalSeedLoader,
                partnerHospitalSeedService
        );
        then(collectionService).should(executionOrder)
                .collectConfiguredRegions();
        then(partnerHospitalSeedLoader).should(executionOrder).load();
        then(partnerHospitalSeedService).should(executionOrder)
                .applyPartnerships(partnerSeedData);
    }
}
