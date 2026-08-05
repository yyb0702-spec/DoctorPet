package com.doctorpet.domain.hospital.publicdata.runner;

import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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

    private final AnimalHospitalRefreshService refreshService;

    @Override
    public void run(ApplicationArguments args) {
        AnimalHospitalRefreshResult result = refreshService.refresh();

        log.info(
                "전국 동물병원 시드 적재 완료: 페이지 {}개, 병원 {}건, 제휴 {}건",
                result.collection().importedPageCount(),
                result.collection().importedHospitalCount(),
                result.appliedPartnerCount()
        );
    }
}
