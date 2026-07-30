package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalSearchPageResponse;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCacheLookupResult;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF"
})
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import(QuerydslConfig.class)
class HospitalSearchCacheBaselineIntegrationTest {

    private static final int TEST_HOSPITAL_COUNT = 20;
    private static final int WARM_UP_COUNT = 20;
    private static final int REQUEST_COUNT = 500;
    private static final int THREAD_COUNT = 32;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<Long> testHospitalIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (!testHospitalIds.isEmpty()) {
            hospitalRepository.deleteAllById(testHospitalIds);
            hospitalRepository.flush();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 캐시_도입_전_최초_진입_페이지의_동시_조회_기준값을_측정한다()
            throws InterruptedException {
        savePartnerHospitals();
        HospitalSearchCacheRepository cacheRepository =
                mock(HospitalSearchCacheRepository.class);
        given(cacheRepository.findInitialPage())
                .willReturn(HospitalSearchCacheLookupResult.miss());
        HospitalService hospitalService = new HospitalService(
                hospitalRepository,
                null,
                null,
                cacheRepository
        );

        for (int index = 0; index < WARM_UP_COUNT; index++) {
            searchInitialPage(hospitalService);
        }

        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        long[] elapsedNanos = new long[REQUEST_COUNT];
        AtomicInteger errorCount = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(REQUEST_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int index = 0; index < REQUEST_COUNT; index++) {
            int requestIndex = index;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    long startedAt = System.nanoTime();
                    searchInitialPage(hospitalService);
                    elapsedNanos[requestIndex] =
                            System.nanoTime() - startedAt;
                } catch (Exception exception) {
                    errorCount.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }

        ready.await();
        long loadStartedAt = System.nanoTime();
        start.countDown();
        boolean completed = finished.await(
                Duration.ofSeconds(60).toMillis(),
                TimeUnit.MILLISECONDS
        );
        long totalElapsedNanos = System.nanoTime() - loadStartedAt;
        executor.shutdownNow();

        Arrays.sort(elapsedNanos);
        double averageMillis = Arrays.stream(elapsedNanos)
                .average()
                .orElseThrow()
                / 1_000_000.0;
        double p50Millis = percentileMillis(elapsedNanos, 0.50);
        double p95Millis = percentileMillis(elapsedNanos, 0.95);
        double throughput = REQUEST_COUNT
                / (totalElapsedNanos / 1_000_000_000.0);

        System.out.printf(
                "HOSPITAL_SEARCH_CACHE_BASELINE "
                        + "requests=%d threads=%d averageMs=%.3f "
                        + "p50Ms=%.3f p95Ms=%.3f throughputRps=%.3f "
                        + "errors=%d queryExecutionCount=%d "
                        + "preparedStatementCount=%d%n",
                REQUEST_COUNT,
                THREAD_COUNT,
                averageMillis,
                p50Millis,
                p95Millis,
                throughput,
                errorCount.get(),
                statistics.getQueryExecutionCount(),
                statistics.getPrepareStatementCount()
        );

        assertThat(completed).isTrue();
        assertThat(errorCount).hasValue(0);
        assertThat(statistics.getQueryExecutionCount())
                .isEqualTo(REQUEST_COUNT * 2L);
    }

    private HospitalSearchPageResponse searchInitialPage(
            HospitalService hospitalService
    ) {
        return hospitalService.hospitalSearch(
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                false,
                1,
                20,
                "name"
        );
    }

    private double percentileMillis(
            long[] sortedElapsedNanos,
            double percentile
    ) {
        int index = (int) Math.ceil(
                percentile * sortedElapsedNanos.length
        ) - 1;
        return sortedElapsedNanos[index] / 1_000_000.0;
    }

    private void savePartnerHospitals() {
        String runId = UUID.randomUUID().toString();
        List<Hospital> hospitals = new ArrayList<>();

        for (int index = 0; index < TEST_HOSPITAL_COUNT; index++) {
            Hospital hospital = Hospital.createFromPublicData(
                    "CACHE-BASELINE-" + runId + "-" + index,
                    "CACHE-LOCAL-GOV",
                    "캐시 기준 측정 동물병원 " + index,
                    "02-1234-5678",
                    "서울특별시 중구 기준 지번주소",
                    "서울특별시 중구 기준 도로명주소",
                    "01234",
                    new BigDecimal("126.9780"),
                    new BigDecimal("37.5665"),
                    null,
                    BusinessStatus.OPEN,
                    null,
                    null,
                    null
            );
            hospital.markAsPartner();
            hospitals.add(hospital);
        }

        hospitalRepository.saveAllAndFlush(hospitals);
        testHospitalIds.addAll(
                hospitals.stream()
                        .map(Hospital::getId)
                        .toList()
        );
    }
}
