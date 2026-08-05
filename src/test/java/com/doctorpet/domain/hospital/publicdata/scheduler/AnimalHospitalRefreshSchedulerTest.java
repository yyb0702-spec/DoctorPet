package com.doctorpet.domain.hospital.publicdata.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;

import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalCollectionResult;
import com.doctorpet.domain.hospital.publicdata.model.AnimalHospitalRefreshResult;
import com.doctorpet.domain.hospital.publicdata.service.AnimalHospitalRefreshService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnimalHospitalRefreshSchedulerTest {

    @Mock
    private AnimalHospitalRefreshService refreshService;
    @Mock
    private AnimalHospitalRefreshLock refreshLock;

    @Test
    void 잠금을_획득하면_갱신을_실행한다() {
        AnimalHospitalRefreshResult result =
                new AnimalHospitalRefreshResult(
                        new AnimalHospitalCollectionResult(2, 100),
                        3
                );
        given(refreshLock.executeIfAcquired(any()))
                .willReturn(Optional.of(result));
        AnimalHospitalRefreshScheduler scheduler =
                new AnimalHospitalRefreshScheduler(refreshService, refreshLock);

        scheduler.refresh();

        then(refreshLock).should().executeIfAcquired(any());
    }

    @Test
    void 다른_인스턴스가_실행_중이면_갱신을_건너뛴다() {
        given(refreshLock.executeIfAcquired(any()))
                .willReturn(Optional.empty());
        AnimalHospitalRefreshScheduler scheduler =
                new AnimalHospitalRefreshScheduler(refreshService, refreshLock);

        scheduler.refresh();

        then(refreshService).shouldHaveNoInteractions();
    }
}
