package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.hospital.dto.response.FavoriteHospitalPageResponse;
import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalFavoriteRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.member.service.MemberWithdrawalApplicationService;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.security.JwtProperties;
import com.doctorpet.global.security.JwtTokenProvider;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

    @Autowired
    private MemberWithdrawalApplicationService memberWithdrawalApplicationService;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private JwtProperties jwtProperties;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

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
    void 내_찜_목록은_최신순과_ID_역순으로_안정적으로_페이징한다() {
        Long memberId = uniqueMemberId();
        Hospital first = saveHospital(BusinessStatus.OPEN);
        Hospital second = saveHospital(BusinessStatus.OPEN);
        Hospital old = saveHospital(BusinessStatus.OPEN);
        hospitalFavoriteService.addFavorite(memberId, first.getId());
        hospitalFavoriteService.addFavorite(memberId, second.getId());
        hospitalFavoriteService.addFavorite(memberId, old.getId());

        updateFavoritedAt(memberId, first.getId(), "2026-08-08 10:00:00.000000");
        updateFavoritedAt(memberId, second.getId(), "2026-08-08 10:00:00.000000");
        updateFavoritedAt(memberId, old.getId(), "2026-08-07 10:00:00.000000");

        FavoriteHospitalPageResponse firstPage =
                hospitalFavoriteService.getMyFavorites(memberId, 1, 2);
        FavoriteHospitalPageResponse secondPage =
                hospitalFavoriteService.getMyFavorites(memberId, 2, 2);

        assertThat(firstPage.content())
                .extracting(favorite -> favorite.hospitalId())
                .containsExactly(second.getId(), first.getId());
        assertThat(firstPage.totalElements()).isEqualTo(3L);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.first()).isTrue();
        assertThat(firstPage.last()).isFalse();
        assertThat(secondPage.content())
                .extracting(favorite -> favorite.hospitalId())
                .containsExactly(old.getId());
        assertThat(secondPage.first()).isFalse();
        assertThat(secondPage.last()).isTrue();
    }

    @Test
    void 회원_탈퇴는_해당_회원의_찜만_삭제한다() {
        Long withdrawnMemberId = uniqueMemberId();
        Long otherMemberId = uniqueMemberId();
        Hospital hospital = saveHospital(BusinessStatus.OPEN);
        hospitalFavoriteService.addFavorite(
                withdrawnMemberId,
                hospital.getId()
        );
        hospitalFavoriteService.addFavorite(otherMemberId, hospital.getId());
        given(reservationService.hasActiveReservation(withdrawnMemberId))
                .willReturn(false);
        given(jwtProperties.getAccessTokenExpiration())
                .willReturn(3_600_000L);

        memberWithdrawalApplicationService.withdraw(withdrawnMemberId);

        assertThat(hospitalFavoriteRepository.countByMemberIdAndHospitalId(
                withdrawnMemberId,
                hospital.getId()
        )).isZero();
        assertThat(hospitalFavoriteRepository.countByMemberIdAndHospitalId(
                otherMemberId,
                hospital.getId()
        )).isEqualTo(1L);
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

    private void updateFavoritedAt(
            Long memberId,
            Long hospitalId,
            String favoritedAt
    ) {
        jdbcTemplate.update(
                """
                UPDATE hospital_favorites
                SET created_at = ?
                WHERE member_id = ? AND hospital_id = ?
                """,
                favoritedAt,
                memberId,
                hospitalId
        );
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
