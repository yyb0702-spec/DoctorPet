package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.hospital.dto.response.FavoriteHospitalPageResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalFavoriteRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ai.gateway=fake",
        "ai.openai.api-key=test"
})
class HospitalFavoriteIntegrationTest {

    @Autowired
    private HospitalFavoriteService hospitalFavoriteService;

    @Autowired
    private HospitalFavoriteRepository hospitalFavoriteRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        deleteTestData();
    }

    @AfterEach
    void tearDown() {
        deleteTestData();
    }

    @Test
    void 같은_병원을_동시에_찜해도_모든_요청이_성공하고_한_건만_남는다()
            throws Exception {
        Long memberId = uniqueMemberId();
        Hospital hospital = saveHospital(BusinessStatus.OPEN);
        int requestCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    hospitalFavoriteService.addFavorite(
                            memberId,
                            hospital.getId()
                    );
                    return null;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(hospitalFavoriteRepository
                .countByMemberIdAndHospitalId(
                        memberId,
                        hospital.getId()
                )).isEqualTo(1L);
    }

    @Test
    void 폐업한_병원도_내_찜_목록에_상태와_함께_반환한다() {
        Long memberId = uniqueMemberId();
        Hospital hospital = saveHospital(BusinessStatus.CLOSED);
        hospitalFavoriteService.addFavorite(memberId, hospital.getId());

        FavoriteHospitalPageResponse response =
                hospitalFavoriteService.getMyFavorites(memberId, 1, 20);

        assertThat(response.content())
                .singleElement()
                .satisfies(favorite -> {
                    assertThat(favorite.hospitalId())
                            .isEqualTo(hospital.getId());
                    assertThat(favorite.businessStatus())
                            .isEqualTo(BusinessStatus.CLOSED);
                    assertThat(favorite.favorite()).isTrue();
                });
    }

    @Test
    void 찜_해제를_반복해도_최종_상태는_해제로_유지된다() {
        Long memberId = uniqueMemberId();
        Hospital hospital = saveHospital(BusinessStatus.OPEN);
        hospitalFavoriteService.addFavorite(memberId, hospital.getId());

        hospitalFavoriteService.removeFavorite(memberId, hospital.getId());
        hospitalFavoriteService.removeFavorite(memberId, hospital.getId());

        assertThat(hospitalFavoriteRepository
                .countByMemberIdAndHospitalId(
                        memberId,
                        hospital.getId()
                )).isZero();
    }

    private Long uniqueMemberId() {
        long value = UUID.randomUUID().getMostSignificantBits()
                & Long.MAX_VALUE;
        Long memberId = value == 0 ? 1L : value;
        return memberId;
    }

    private Hospital saveHospital(BusinessStatus businessStatus) {
        String key = UUID.randomUUID().toString();
        Hospital hospital = Hospital.createFromPublicData(
                "FAVORITE-" + key,
                "FAVORITE-TEST",
                "찜 테스트 병원",
                "02-1234-5678",
                "서울특별시 중구",
                "서울특별시 중구 세종대로",
                "01234",
                null,
                null,
                null,
                businessStatus,
                null,
                null,
                null
        );
        hospitalRepository.saveAndFlush(hospital);
        return hospital;
    }

    private void deleteTestData() {
        jdbcTemplate.update("""
                DELETE favorite
                FROM hospital_favorites favorite
                INNER JOIN hospitals hospital
                    ON hospital.id = favorite.hospital_id
                WHERE hospital.local_gov_code = ?
                """, "FAVORITE-TEST");
        jdbcTemplate.update(
                "DELETE FROM hospitals WHERE local_gov_code = ?",
                "FAVORITE-TEST"
        );
    }
}
