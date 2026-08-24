package com.doctorpet.domain.hospital.publicdata.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.doctorpet.domain.hospital.publicdata.config.AnimalHospitalApiProperties;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalRefreshService;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalRefreshSchedulerTest {

    @Mock
    private AnimalHospitalRefreshService refreshService;
    @Mock
    private AnimalHospitalRefreshLock refreshLock;
    @Mock
    private TaskScheduler taskScheduler;

    private AnimalHospitalRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        AnimalHospitalApiProperties properties = new AnimalHospitalApiProperties(
                null,
                null,
                100,
                null,
                null,
                null,
                3,
                Duration.ofMinutes(5)
        );
        scheduler = new AnimalHospitalRefreshScheduler(
                refreshService,
                refreshLock,
                properties,
                taskScheduler
        );
    }

    @Test
    void 공공데이터_갱신은_매일_오전_3시로_설정한다() throws Exception {
        Method refreshMethod = AnimalHospitalRefreshScheduler.class
                .getDeclaredMethod("refresh");
        Scheduled scheduled = refreshMethod.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron())
                .isEqualTo("${public-data.animal-hospital.refresh-cron:0 0 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");

        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader().load(
                "application.yaml",
                new FileSystemResource("src/main/resources/application.yaml")
        );
        assertThat(propertySources)
                .extracting(source -> source.getProperty(
                        "public-data.animal-hospital.refresh-cron"
                ))
                .filteredOn(value -> value != null)
                .containsExactly("${PUBLIC_DATA_REFRESH_CRON:0 0 3 * * *}");
    }

    @Test
    void 잠금을_획득하면_갱신을_실행하고_재시도하지_않는다() {
        given(refreshLock.executeIfAcquired(any()))
                .willReturn(Optional.of(refreshResult()));

        scheduler.refresh();

        then(refreshLock).should().executeIfAcquired(any());
        then(taskScheduler).shouldHaveNoInteractions();
    }

    @Test
    void 다른_인스턴스가_실행_중이면_갱신과_재시도를_건너뛴다() {
        given(refreshLock.executeIfAcquired(any()))
                .willReturn(Optional.empty());

        scheduler.refresh();

        then(refreshService).shouldHaveNoInteractions();
        then(taskScheduler).shouldHaveNoInteractions();
    }

    @Test
    void 실패하면_재시도하고_성공한_뒤에는_추가_재시도하지_않는다() {
        Queue<Runnable> scheduledRetries = captureScheduledRetries();
        given(refreshLock.executeIfAcquired(any()))
                .willThrow(new IllegalStateException("temporary failure"))
                .willReturn(Optional.of(refreshResult()));

        scheduler.refresh();
        assertThat(scheduledRetries).hasSize(1);

        scheduledRetries.remove().run();

        then(refreshLock).should(times(2)).executeIfAcquired(any());
        assertThat(scheduledRetries).isEmpty();
    }

    @Test
    void 최대_시도_횟수에_도달하면_더_이상_재시도하지_않는다() {
        Queue<Runnable> scheduledRetries = captureScheduledRetries();
        given(refreshLock.executeIfAcquired(any()))
                .willThrow(new IllegalStateException("persistent failure"));

        scheduler.refresh();
        scheduledRetries.remove().run();
        scheduledRetries.remove().run();

        then(refreshLock).should(times(3)).executeIfAcquired(any());
        then(taskScheduler).should(times(2))
                .schedule(any(Runnable.class), any(Instant.class));
        assertThat(scheduledRetries).isEmpty();
    }

    private Queue<Runnable> captureScheduledRetries() {
        Queue<Runnable> scheduledRetries = new ArrayDeque<>();
        given(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .willAnswer(invocation -> {
                    scheduledRetries.add(invocation.getArgument(0));
                    return null;
                });
        return scheduledRetries;
    }

    private AnimalHospitalRefreshResult refreshResult() {
        return new AnimalHospitalRefreshResult(
                new AnimalHospitalCollectionResult(2, 100),
                3
        );
    }
}
