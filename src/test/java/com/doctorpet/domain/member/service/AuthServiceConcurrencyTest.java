package com.doctorpet.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.dto.request.LoginRequest;
import com.doctorpet.domain.member.dto.request.ReissueRequest;
import com.doctorpet.domain.member.dto.response.LoginResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.repository.RefreshTokenRepository;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Level 3 — 동시성 통합 검증(docs/testing/verification-guide.md, SA 부록 B).
 * 리뷰에서 지적된 두 P1(로그인 실패 카운트 lost update, Refresh Token 회전 경쟁 상태)을
 * 실제 MySQL·Redis(비관적 락·Lua 원자 연산)로 검증한다. Mockito 슬라이스로는 락·원자성 자체를
 * 확인할 수 없다. 로컬 application-local.yml 또는 CI services 컨테이너(MySQL 8·Redis 7)가 필요하다.
 */
@SpringBootTest
class AuthServiceConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 10;
    private static final String CORRECT_PASSWORD = "correct-password1234";

    @Autowired
    private AuthService authService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long memberId;
    private String email;

    @BeforeEach
    void setUp() {
        email = "concurrency-" + System.nanoTime() + "@example.com";
        Member member = Member.createGuardian(email, passwordEncoder.encode(CORRECT_PASSWORD), "동시성테스트");
        member.verifyEmail(); // 로그인 테스트 대상 — 이메일 인증 필수 정책(§6-4)으로 인증 상태로 준비한다.
        memberId = memberRepository.saveAndFlush(member).getId();
    }

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteByMemberId(memberId);
    }

    @Test
    @DisplayName("동일 계정에 대한 동시 로그인 실패 요청은 lost update 없이 정확히 반영된다(비관적 락)")
    void concurrentLoginFailures_incrementAtomically() throws InterruptedException {
        LoginRequest wrongPasswordRequest = new LoginRequest(email, "wrong-password");

        runConcurrently(CONCURRENT_REQUESTS, () -> {
            try {
                authService.login(wrongPasswordRequest);
            } catch (ServiceException ignored) {
                // 비밀번호 불일치·계정 잠금 모두 예상된 예외 — 여기서는 최종 실패 카운트만 확인한다.
            }
        });

        Member reloaded = memberRepository.findById(memberId).orElseThrow();
        // 임계값(5회)에서 잠기므로 그 이상은 증가하지 않는다 — 핵심은 5 미만으로 유실되지 않는 것이다.
        assertThat(reloaded.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(reloaded.isLocked()).isTrue();
    }

    @Test
    @DisplayName("동일 Refresh Token으로 동시에 재발급을 요청하면 락으로 직렬화되어 정확히 하나만 성공하고, 그 새 토큰은 삭제되지 않는다")
    void concurrentReissue_onlyOneSucceeds() throws InterruptedException {
        // 리뷰 대응 이력: 처음엔 CAS 실패 시 무조건 세션을 삭제해 승자의 새 토큰까지 지워지는
        // 버그가 있었고, 그다음엔 5초 유예 창으로 "동시 중복"과 "진짜 재사용"을 구분했지만 그 창
        // 안에서 탈취 토큰 재사용을 놓칠 수 있다는 지적을 받았다. 지금은 회원당 재발급을 Redis
        // 락(tryLock/unlock)으로 직렬화해 애초에 여러 요청이 동시에 CAS를 다투지 않게 한다 —
        // 시간 기반 추측 없이도 승자의 세션이 안전하다.
        String refreshToken = authService.login(new LoginRequest(email, CORRECT_PASSWORD)).refreshToken();
        ReissueRequest reissueRequest = new ReissueRequest(refreshToken);
        List<Boolean> results = new CopyOnWriteArrayList<>();
        List<LoginResponse> successes = new CopyOnWriteArrayList<>();

        runConcurrently(CONCURRENT_REQUESTS, () -> {
            try {
                LoginResponse response = authService.reissue(reissueRequest);
                successes.add(response);
                results.add(true);
            } catch (ServiceException e) {
                // 락을 못 얻은 요청은 REISSUE_IN_PROGRESS(409), CAS에 실패한 요청은
                // REFRESH_TOKEN_REUSED(401) — 둘 다 "성공하지 못함"으로만 집계한다.
                results.add(false);
            }
        });

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(1);

        // 핵심 회귀 검증: 나머지 요청들이 승자가 방금 받은 새 Refresh Token을 지워버리지 않아야 한다.
        String winningRefreshToken = successes.get(0).refreshToken();
        assertThat(refreshTokenRepository.findByMemberId(memberId)).contains(winningRefreshToken);
    }

    /** N개의 작업을 모두 준비시킨 뒤 동시에 출발시켜, 진짜 경쟁 상태를 재현한다. */
    private void runConcurrently(int count, Runnable task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch readyLatch = new CountDownLatch(count);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
    }
}
