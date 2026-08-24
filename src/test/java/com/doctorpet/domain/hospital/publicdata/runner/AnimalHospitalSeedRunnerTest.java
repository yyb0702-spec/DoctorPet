package com.doctorpet.domain.hospital.publicdata.runner;

import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalRefreshService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalSeedRunnerTest {

    @Mock
    private AnimalHospitalRefreshService refreshService;

    @Test
    void 실행되면_공공데이터_적재_후_제휴_데이터를_적용한다() {
        given(refreshService.seed()).willReturn(
                new AnimalHospitalRefreshResult(
                        new AnimalHospitalCollectionResult(2, 150),
                        1
                )
        );
        AnimalHospitalSeedRunner runner = new AnimalHospitalSeedRunner(refreshService);

        // 제휴 대상 병원이 존재하도록 공공데이터 수집이 항상 먼저 실행되는지 확인합니다.
        runner.run(null);

        then(refreshService).should().seed();
    }
}
