package com.doctorpet.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.payment.dto.response.PaymentChargeResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.global.crypto.BillingKeyCryptor;
import com.doctorpet.global.gateway.payment.GatewayFailureReason;
import com.doctorpet.global.gateway.payment.GatewayPaymentStatus;
import com.doctorpet.global.gateway.payment.PaymentGateway;
import com.doctorpet.global.gateway.payment.PaymentGatewayException;
import com.doctorpet.global.gateway.payment.dto.PaymentQueryResult;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 3 — 재시도 백오프 데드라인 조기 종료 시 실제 DB 조건부 갱신 검증(STRICT, SA §9-4 재시도 분기, PR #92 P1).
 * 기존 Level 1(PaymentApplicationServiceTest)은 PaymentChargeService·RetryBackoff를 목으로 두어 실제
 * remainPendingIfPending 조건부 UPDATE로 payments.retry_count·failure_reason이 어떻게 남는지 확인하지 못한다.
 * 여기서는 실제 MySQL·실제 PaymentChargeService 배선을 그대로 타고, 게이트웨이와 백오프만 목으로 두어
 * 데드라인 소진을 결정론적으로 유발한다 — retry_count에 무조건 maxRetry가 아니라 실제 수행한 재시도 횟수가
 * 저장되는지(#92 P2 회귀 방지), 그리고 정산 스케줄러(#35)가 읽는 그 값이 실제와 맞는지 검증한다.
 *
 * <p>백오프 pause를 2000ms로 고정하고 데드라인 예산은 기본 3500ms이므로 attempt1(누적 2000)·attempt2(누적
 * 4000)까지만 수행하고 attempt3(maxRetry의 마지막 회차)은 예산 소진으로 건너뛴다 → 실제 재시도는 2회다.
 * 게이트웨이 query가 미확정(PENDING)을 반환하므로 최종 확정은 오프라인 이중수납 금지를 위해 PENDING을 유지한다.
 * 전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest
class PaymentRetryDeadlineIntegrationTest {

    private static final Long HOSPITAL_ID = 5252L;
    private static final int AMOUNT = 50_000;
    // application.yaml 기본값 — 백오프 pause 2000ms를 두 번 누적(4000ms)하면 초과해 3회차 재시도를 막는 예산.
    private static final long BACKOFF_PAUSE_MS = 2_000L;
    private static final int EXPECTED_PERFORMED_RETRIES = 2;

    @Autowired private PaymentApplicationService paymentApplicationService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentMethodRepository paymentMethodRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private BillingKeyCryptor billingKeyCryptor;

    // 결정론적 조기 종료를 위해 게이트웨이·백오프만 목으로 대체한다. 나머지(트랜잭션 경계·조건부 UPDATE·엔티티)는 실배선.
    @MockitoBean private PaymentGateway paymentGateway;
    @MockitoBean private RetryBackoff retryBackoff;

    private Long staffMemberId;
    private Long guardianMemberId;
    private Long paymentMethodId;
    private Long reservationId;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        Member staff = Member.createGuardian("staff-" + nano + "@example.com", "pw", "스태프");
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", HOSPITAL_ID);
        staffMemberId = memberRepository.saveAndFlush(staff).getId();

        guardianMemberId = memberRepository.saveAndFlush(
                Member.createGuardian("guardian-" + nano + "@example.com", "pw", "보호자")).getId();

        paymentMethodId = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(guardianMemberId, billingKeyCryptor.encrypt("billing-key"), "VISA", "1234")).getId();

        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = Reservation.request(
                guardianMemberId, 1L, HOSPITAL_ID, System.nanoTime(), paymentMethodId, "나비", "CAT", now);
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.TREATMENT_COMPLETED);
        ReflectionTestUtils.setField(reservation, "confirmedAt", now);
        reservationId = reservationRepository.saveAndFlush(reservation).getId();

        // 승인은 항상 재시도 유효 실패, 단건조회는 미확정 → 재시도 루프 진입 후 데드라인으로 조기 종료.
        given(retryBackoff.pause(anyInt(), anyLong())).willReturn(BACKOFF_PAUSE_MS);
        given(paymentGateway.approve(any()))
                .willThrow(new PaymentGatewayException(GatewayFailureReason.RETRIABLE, "TIMEOUT", "일시 장애"));
        given(paymentGateway.query(anyString()))
                .willReturn(new PaymentQueryResult(GatewayPaymentStatus.PENDING, null, 0));
    }

    @AfterEach
    void tearDown() {
        paymentRepository.findByReservationId(reservationId).ifPresent(paymentRepository::delete);
        reservationRepository.deleteById(reservationId);
        paymentMethodRepository.deleteById(paymentMethodId);
        memberRepository.deleteById(staffMemberId);
        memberRepository.deleteById(guardianMemberId);
    }

    @Test
    @DisplayName("데드라인으로 재시도가 조기 종료되면 payments.retry_count에 maxRetry가 아니라 실제 수행한 재시도 횟수가 저장된다")
    void deadlineEarlyTermination_persistsActualRetryCount() {
        PaymentChargeResponse response =
                paymentApplicationService.charge(reservationId, staffMemberId, AMOUNT);

        // 승인 여부 미상이라 오프라인 이중수납 금지 → PENDING 유지(정산 스케줄러가 확정).
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);

        // 실제 MySQL 조건부 UPDATE(remainPendingIfPending) 결과를 다시 읽어 확인한다.
        Payment saved = paymentRepository.findByReservationId(reservationId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
        // 핵심(#92 P2 회귀 방지): 조기 종료 시 무조건 maxRetry가 아니라 실제 수행한 재시도 횟수(2)가 저장된다.
        assertThat(saved.getRetryCount()).isEqualTo(EXPECTED_PERFORMED_RETRIES);
        assertThat(saved.getFailureReason()).isEqualTo("RETRY_EXHAUSTED_UNCONFIRMED");
    }
}
