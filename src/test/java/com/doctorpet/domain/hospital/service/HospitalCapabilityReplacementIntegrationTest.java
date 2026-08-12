package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.hospital.dto.request.HospitalCapabilitiesUpdateRequest;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.CapabilityValue;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalCapabilityRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("local")
@SpringBootTest(properties = {
        "ai.openai.api-key=test-key",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "public-data.animal-hospital.seed-enabled=false",
        "public-data.animal-hospital.refresh-enabled=false"
})
class HospitalCapabilityReplacementIntegrationTest {

    private static final Long MEMBER_ID = 91_160L;

    @Autowired
    private HospitalCapabilityApplicationService service;
    @Autowired
    private HospitalRepository hospitalRepository;
    @Autowired
    private HospitalCapabilityRepository capabilityRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @MockitoBean
    private MemberService memberService;

    private Long hospitalId;

    @BeforeEach
    void setUp() {
        Hospital hospital = hospitalRepository.saveAndFlush(createHospital());
        hospitalId = hospital.getId();
        given(memberService.getMyInfo(MEMBER_ID)).willReturn(new MemberResponse(
                MEMBER_ID,
                "capability-staff@example.com",
                "capability-staff",
                "010-1234-5678",
                MemberRole.HOSPITAL_STAFF,
                hospitalId
        ));
    }

    @AfterEach
    void cleanUp() {
        if (hospitalId != null) {
            transactionTemplate.executeWithoutResult(status -> {
                capabilityRepository.deleteAllByHospitalId(hospitalId);
                hospitalRepository.deleteById(hospitalId);
            });
        }
    }

    @Test
    void concurrentFullReplacementsLeaveExactlyOneCompleteRequest() throws Exception {
        HospitalCapabilitiesUpdateRequest first = new HospitalCapabilitiesUpdateRequest(
                List.of(CapabilityValue.DOG, CapabilityValue.XRAY)
        );
        HospitalCapabilitiesUpdateRequest second = new HospitalCapabilitiesUpdateRequest(
                List.of(CapabilityValue.CAT, CapabilityValue.MRI)
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstFuture = executor.submit(() -> replaceAfterSignal(first, ready, start));
            Future<?> secondFuture = executor.submit(() -> replaceAfterSignal(second, ready, start));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstFuture.get(30, TimeUnit.SECONDS);
            secondFuture.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Set<CapabilityValue> stored = capabilityRepository
                .findAllByHospitalId(hospitalId)
                .stream()
                .map(capability -> capability.getCapabilityValue())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(stored).isIn(
                Set.of(CapabilityValue.DOG, CapabilityValue.XRAY),
                Set.of(CapabilityValue.CAT, CapabilityValue.MRI)
        );
    }

    private void replaceAfterSignal(
            HospitalCapabilitiesUpdateRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Start signal timed out");
            }
            service.updateCapabilities(MEMBER_ID, request);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Replacement was interrupted", exception);
        }
    }

    private Hospital createHospital() {
        return Hospital.createFromPublicData(
                "CAPABILITY-REPLACE-" + System.nanoTime(),
                "TEST-LOCAL-GOV",
                "역량 교체 테스트 병원",
                "02-1234-5678",
                "서울시 테스트구",
                "서울시 테스트구 테스트로 1",
                "01234",
                new BigDecimal("126.9780"),
                new BigDecimal("37.5665"),
                LocalDate.of(2020, 1, 1),
                BusinessStatus.OPEN,
                null,
                new BigDecimal("100.0"),
                LocalDateTime.of(2026, 8, 12, 0, 0)
        );
    }
}
