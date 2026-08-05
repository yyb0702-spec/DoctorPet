package com.doctorpet.domain.hospital.publicdata.scheduler;

import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalRefreshService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 전국 동물병원 원천 데이터와 제휴 보강 데이터를 주기적으로 갱신합니다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "public-data.animal-hospital",
        name = "refresh-enabled",
        havingValue = "true"
)
public class AnimalHospitalRefreshScheduler {

    private final AnimalHospitalRefreshService refreshService;
    private final AnimalHospitalRefreshLock refreshLock;

    @Scheduled(
            cron = "${public-data.animal-hospital.refresh-cron:0 0 3 * * MON}",
            zone = "Asia/Seoul"
    )
    public void refresh() {
        Optional<AnimalHospitalRefreshResult> result =
                refreshLock.executeIfAcquired(refreshService::refresh);
        if (result.isEmpty()) {
            log.info("동물병원 공공데이터 갱신 스킵: 다른 인스턴스 실행 중");
            return;
        }

        AnimalHospitalRefreshResult refreshed = result.get();
        log.info(
                "전국 동물병원 공공데이터 갱신 완료: 페이지 {}개, 병원 {}건, 제휴 {}건",
                refreshed.collection().importedPageCount(),
                refreshed.collection().importedHospitalCount(),
                refreshed.appliedPartnerCount()
        );
    }
}
