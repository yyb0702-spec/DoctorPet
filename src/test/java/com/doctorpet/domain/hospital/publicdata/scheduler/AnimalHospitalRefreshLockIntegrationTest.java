package com.doctorpet.domain.hospital.publicdata.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.global.config.QuerydslConfig;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=update")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AnimalHospitalRefreshLock.class, QuerydslConfig.class})
class AnimalHospitalRefreshLockIntegrationTest {

    @Autowired
    private AnimalHospitalRefreshLock refreshLock;

    @Test
    void 여러_인스턴스_중_하나만_갱신_잠금을_획득한다() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Optional<Boolean>> first = executor.submit(() ->
                    refreshLock.executeIfAcquired(() -> {
                        acquired.countDown();
                        await(release);
                        return true;
                    })
            );

            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            Optional<Boolean> second =
                    refreshLock.executeIfAcquired(() -> true);

            assertThat(second).isEmpty();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).contains(true);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("공공데이터 갱신 잠금 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
