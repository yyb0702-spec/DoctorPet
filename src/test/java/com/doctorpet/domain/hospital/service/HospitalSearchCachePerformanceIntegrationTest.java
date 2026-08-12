package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCachedPage;
import com.doctorpet.domain.hospital.dto.query.HospitalSearchCandidate;
import com.doctorpet.domain.hospital.entity.PartnershipStatus;
import com.doctorpet.domain.hospital.repository.HospitalSearchCacheRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF"
})
class HospitalSearchCachePerformanceIntegrationTest {

    private static final String CACHE_KEY =
            "hospital-search:initial-page:v1";
    private static final int TEST_HOSPITAL_COUNT = 20;
    private static final int REQUEST_COUNT = 500;
    private static final int THREAD_COUNT = 32;

    @Autowired
    private HospitalService hospitalService;

    @Autowired
    private HospitalSearchCacheRepository cacheRepository;

    @MockitoSpyBean
    private HospitalRepository hospitalRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        redisTemplate.delete(CACHE_KEY);
    }

    @Test
    void 캐시_HIT_최초_진입_페이지의_동시_조회_성능을_측정한다()
            throws InterruptedException {
        redisTemplate.delete(CACHE_KEY);
        cacheRepository.saveInitialPage(cachedPage());
        clearInvocations(hospitalRepository);

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
                    searchInitialPage();
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
        long testStartedAt = System.nanoTime();
        start.countDown();
        boolean completed = finished.await(30, TimeUnit.SECONDS);
        long totalElapsedNanos = System.nanoTime() - testStartedAt;
        executor.shutdown();

        long[] sortedElapsedNanos = Arrays.stream(elapsedNanos)
                .filter(value -> value > 0)
                .sorted()
                .toArray();
        double averageMs = Arrays.stream(sortedElapsedNanos)
                .average()
                .orElse(0.0) / 1_000_000.0;
        double p50Ms = percentile(sortedElapsedNanos, 0.50)
                / 1_000_000.0;
        double p95Ms = percentile(sortedElapsedNanos, 0.95)
                / 1_000_000.0;
        double throughput = REQUEST_COUNT
                / (totalElapsedNanos / 1_000_000_000.0);

        System.out.printf(
                "hospital-search cache HIT: avg=%.3fms, "
                        + "p50=%.3fms, p95=%.3fms, throughput=%.3fRPS, "
                        + "errors=%d, queries=%d, preparedStatements=%d%n",
                averageMs,
                p50Ms,
                p95Ms,
                throughput,
                errorCount.get(),
                statistics.getQueryExecutionCount(),
                statistics.getPrepareStatementCount()
        );

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isZero();
        assertThat(sortedElapsedNanos).hasSize(REQUEST_COUNT);
        verifyNoInteractions(hospitalRepository);
    }

    private HospitalSearchCachedPage cachedPage() {
        List<HospitalSearchCandidate> hospitals = new ArrayList<>();
        for (int index = 0; index < TEST_HOSPITAL_COUNT; index++) {
            hospitals.add(new HospitalSearchCandidate(
                    (long) index + 1,
                    String.format("CACHE-PERF-%02d", index),
                    "서울특별시 중구 도로명주소",
                    "서울특별시 중구 지번주소",
                    new BigDecimal("126.9780"),
                    new BigDecimal("37.5665"),
                    BusinessStatus.OPEN,
                    PartnershipStatus.PARTNER,
                    java.util.Map.of()
            ));
        }
        return new HospitalSearchCachedPage(hospitals, hospitals.size());
    }

    private void searchInitialPage() {
        hospitalService.hospitalSearch(
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

    private long percentile(long[] sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.length) - 1;
        return sortedValues[Math.max(index, 0)];
    }
}
