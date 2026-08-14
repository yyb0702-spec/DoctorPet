package com.doctorpet.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.doctorpet.domain.hospital.support.HospitalDetailTestFixture.partnerHospital;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import com.doctorpet.domain.chat.dto.request.ChatMessageSendRequest;
import com.doctorpet.domain.chat.exception.ChatErrorCode;
import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import com.doctorpet.domain.chat.port.ChatMemberProfilePort;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.service.HospitalReservationApplicationService;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Level 3 — 채팅 저장이 예약 행의 PESSIMISTIC_WRITE를 먼저 잡아도 종료 조건부 UPDATE와 직렬화된다.
 * 종료가 먼저 커밋되면 send는 종료 상태를 다시 읽어 거부되고, send가 먼저 커밋된 경우에만 메시지가 남는다.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false",
        "jwt.secret=doctorpet-chat-integration-test-secret-key-32-bytes-minimum",
        "jwt.access-token-expiration=3600000",
        "jwt.refresh-token-expiration=1209600000"
})
class ChatMessageConcurrencyIntegrationTest {

    @Autowired private ChatMessageService chatMessageService;
    @Autowired private HospitalReservationApplicationService hospitalReservationService;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSlotRepository reservationSlotRepository;
    @Autowired private MemberRepository memberRepository;
    @MockitoSpyBean private ReservationService reservationService;
    @MockitoBean private HospitalService hospitalService;
    @MockitoBean private ChatMemberProfilePort memberProfilePort;

    private Long reservationId;
    private Long slotId;
    private Long staffMemberId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            chatMessageRepository.deleteAll(chatMessageRepository
                    .findByReservationIdOrderByCreatedAtAscIdAsc(reservationId,
                            org.springframework.data.domain.Pageable.unpaged()));
            reservationRepository.deleteById(reservationId);
        }
        if (slotId != null) {
            reservationSlotRepository.deleteById(slotId);
        }
        if (staffMemberId != null) {
            memberRepository.deleteById(staffMemberId);
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("종료 전이가 먼저 커밋되면 이미 시작된 전송도 잠금 재조회 후 거부된다")
    void sendAfterCompletedTreatmentCommit_isRejectedWithoutPersistingMessage() throws Exception {
        RaceData data = saveInTreatmentReservation();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch sendStartedBeforeLock = new CountDownLatch(1);
        CountDownLatch allowSendToLock = new CountDownLatch(1);

        doAnswer(invocation -> {
            sendStartedBeforeLock.countDown();
            assertThat(allowSendToLock.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(reservationService).findReservationForChatForUpdate(eq(data.reservationId()));

        try {
            Future<?> send = executor.submit(() -> chatMessageService.send(
                    data.reservationId(), data.guardian(), new ChatMessageSendRequest("진료 중 문의")));
            assertThat(sendStartedBeforeLock.await(10, TimeUnit.SECONDS)).isTrue();

            // completeTreatment()의 트랜잭션이 반환하기 전에 커밋까지 끝난다. 따라서 뒤이어
            // 전송이 예약 행 잠금을 얻으면 반드시 TREATMENT_COMPLETED를 다시 읽어야 한다.
            hospitalReservationService.completeTreatment(data.staffMemberId(), data.reservationId());
            allowSendToLock.countDown();

            Throwable failure = catchThrowable(() -> send.get(10, TimeUnit.SECONDS));
            assertThat(failure).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(failure.getCause()).isInstanceOf(ServiceException.class);
            assertThat(((ServiceException) failure.getCause()).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_SEND_NOT_ALLOWED);
        } finally {
            allowSendToLock.countDown();
            executor.shutdownNow();
        }

        assertThat(reservationRepository.findById(data.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.TREATMENT_COMPLETED);
        assertThat(chatMessageRepository
                .findByReservationIdOrderByCreatedAtAscIdAsc(data.reservationId(),
                        org.springframework.data.domain.Pageable.unpaged()))
                .isEmpty();
    }

    private RaceData saveInTreatmentReservation() {
        long hospitalId = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                hospitalId, now.plusDays(2), now.plusDays(2).plusMinutes(30)));
        slot.reserve();
        reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        Member staff = Member.createGuardian(
                "chat-race-" + hospitalId + "@example.com", "encoded", "스태프");
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        staff = memberRepository.saveAndFlush(staff);
        staffMemberId = staff.getId();

        MemberPrincipal guardian = new MemberPrincipal(hospitalId + 1, "guardian@example.com",
                MemberRole.GUARDIAN.name());
        Reservation reservation = Reservation.request(
                guardian.memberId(), 1L, hospitalId, slot.getId(), 1L,
                "초코", "DOG", now, slot.getStartAt());
        ReflectionTestUtils.setField(reservation, "status", ReservationStatus.IN_TREATMENT);
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationId = reservation.getId();

        given(hospitalService.getHospitalDetail(anyLong()))
                .willReturn(partnerHospital(hospitalId, "테스트동물병원"));
        given(memberProfilePort.getGuardianNickname(anyLong())).willReturn("테스트보호자");
        return new RaceData(reservation.getId(), staff.getId(), guardian);
    }

    private record RaceData(Long reservationId, Long staffMemberId, MemberPrincipal guardian) {
    }
}
