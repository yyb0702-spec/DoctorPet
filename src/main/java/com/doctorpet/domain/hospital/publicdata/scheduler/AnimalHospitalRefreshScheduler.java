package com.doctorpet.domain.hospital.publicdata.scheduler;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalRefreshService;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.TaskScheduler;
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
    private final AnimalHospitalApiProperties properties;
    private final TaskScheduler taskScheduler;

    @Scheduled(
            cron = "${public-data.animal-hospital.refresh-cron:0 0 3 * * *}",
            zone = "Asia/Seoul"
    )
    public void refresh() {
        executeAttempt(1);
    }

    private void executeAttempt(int attempt) {
        try {
            Optional<AnimalHospitalRefreshResult> result =
                    refreshLock.executeIfAcquired(refreshService::refresh);
            if (result.isEmpty()) {
                log.info("동물병원 공공데이터 갱신 스킵: 다른 인스턴스 실행 중");
                return;
            }

            logSuccess(result.get(), attempt);
        } catch (RuntimeException exception) {
            scheduleRetryOrLogFailure(attempt, exception);
        }
    }

    private void logSuccess(
            AnimalHospitalRefreshResult refreshed,
            int attempt
    ) {
        log.info(
                "전국 동물병원 공공데이터 갱신 완료: 시도 {}/{}, 페이지 {}개, 병원 {}건, 제휴 {}건",
                attempt,
                properties.refreshMaxAttempts(),
                refreshed.collection().importedPageCount(),
                refreshed.collection().importedHospitalCount(),
                refreshed.appliedPartnerCount()
        );
    }

    private void scheduleRetryOrLogFailure(
            int attempt,
            RuntimeException exception
    ) {
        if (attempt >= properties.refreshMaxAttempts()) {
            log.error(
                    "전국 동물병원 공공데이터 갱신 최종 실패: 시도 {}/{}",
                    attempt,
                    properties.refreshMaxAttempts(),
                    exception
            );
            return;
        }

        int nextAttempt = attempt + 1;
        Instant retryAt = Instant.now().plus(
                properties.refreshRetryDelay()
        );
        taskScheduler.schedule(
                () -> executeAttempt(nextAttempt),
                retryAt
        );
        log.warn(
                "전국 동물병원 공공데이터 갱신 실패, 재시도 예약: 다음 시도 {}/{}, retryAt={}",
                nextAttempt,
                properties.refreshMaxAttempts(),
                retryAt,
                exception
        );
    }
}
