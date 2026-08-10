package com.doctorpet.domain.hospital.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalFavoriteRepository;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.member.service.MemberWithdrawalApplicationService;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.JwtProperties;
import com.doctorpet.global.security.JwtTokenProvider;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ai.gateway=fake",
        "ai.openai.api-key=test"
})
class HospitalFavoriteWithdrawalConcurrencyIntegrationTest {

    @Autowired
    private HospitalFavoriteService hospitalFavoriteService;

    @Autowired
    private HospitalFavoriteRepository hospitalFavoriteRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberWithdrawalApplicationService withdrawalService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private JwtProperties jwtProperties;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    private Long memberId;
    private Long hospitalId;

    @AfterEach
    void tearDown() {
        if (memberId != null) {
            jdbcTemplate.update(
                    "DELETE FROM hospital_favorites WHERE member_id = ?",
                    memberId
            );
            jdbcTemplate.update("DELETE FROM members WHERE id = ?", memberId);
        }
        if (hospitalId != null) {
            hospitalRepository.deleteById(hospitalId);
        }
    }

    @Test
    void 탈퇴가_회원_잠금을_선점하면_동시_찜은_탈퇴_완료_후_거부된다()
            throws Exception {
        String key = UUID.randomUUID().toString();
        Member member = memberRepository.saveAndFlush(Member.createGuardian(
                key + "@example.com",
                "encoded-password",
                "동시성 테스트"
        ));
        Hospital hospital = hospitalRepository.saveAndFlush(
                Hospital.createFromPublicData(
                        "FAVORITE-WITHDRAW-" + key,
                        "FAVORITE-WITHDRAW-TEST",
                        "찜 탈퇴 경합 테스트 병원",
                        "02-1234-5678",
                        "서울특별시 중구",
                        "서울특별시 중구 세종대로",
                        "01234",
                        null,
                        null,
                        null,
                        BusinessStatus.OPEN,
                        null,
                        null,
                        null
                )
        );
        memberId = member.getId();
        hospitalId = hospital.getId();
        given(reservationService.hasActiveReservation(memberId))
                .willReturn(false);
        given(jwtProperties.getAccessTokenExpiration())
                .willReturn(3_600_000L);

        CountDownLatch withdrawalLocked = new CountDownLatch(1);
        CountDownLatch continueWithdrawal = new CountDownLatch(1);
        CountDownLatch favoriteStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> withdrawal = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    memberService.lockActiveMember(memberId);
                    withdrawalLocked.countDown();
                    await(continueWithdrawal);
                    withdrawalService.withdraw(memberId);
                });
                return null;
            });
            assertThat(withdrawalLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> favorite = executor.submit(() -> {
                favoriteStarted.countDown();
                hospitalFavoriteService.addFavorite(memberId, hospitalId);
                return null;
            });

            assertThat(favoriteStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatFavoriteWaitsForMemberLock(favorite);
            continueWithdrawal.countDown();
            withdrawal.get(10, TimeUnit.SECONDS);

            assertThatThrownByFuture(favorite);
        } finally {
            continueWithdrawal.countDown();
            executor.shutdownNow();
        }

        assertThat(hospitalFavoriteRepository.countByMemberIdAndHospitalId(
                memberId,
                hospitalId
        )).isZero();
    }

    private void assertThatFavoriteWaitsForMemberLock(Future<?> future)
            throws Exception {
        try {
            future.get(300, TimeUnit.MILLISECONDS);
            throw new AssertionError("회원 잠금 중에 찜 등록이 완료됐습니다.");
        } catch (TimeoutException expected) {
            // 탈퇴 트랜잭션이 잡은 회원 행 잠금이 풀릴 때까지 기다리는 것이 정상이다.
        }
    }

    private void assertThatThrownByFuture(Future<?> future) throws Exception {
        try {
            future.get(10, TimeUnit.SECONDS);
            throw new AssertionError("탈퇴 회원의 찜 등록이 성공했습니다.");
        } catch (ExecutionException exception) {
            assertThat(exception.getCause())
                    .isInstanceOfSatisfying(ServiceException.class, cause ->
                            assertThat(cause.getErrorCode())
                                    .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
