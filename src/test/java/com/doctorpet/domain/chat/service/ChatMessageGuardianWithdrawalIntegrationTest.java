package com.doctorpet.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.doctorpet.domain.chat.dto.request.ChatMessageSendRequest;
import com.doctorpet.domain.chat.entity.ChatMessage;
import com.doctorpet.domain.chat.repository.ChatMessageRepository;
import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.security.MemberPrincipal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/** Level 3 — 탈퇴 회원의 Soft Delete가 채팅 1년 보존·병원 이력 조회를 끊지 않는지 검증한다. */
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
class ChatMessageGuardianWithdrawalIntegrationTest {

    @Autowired private ChatMessageService chatMessageService;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private MemberService memberService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSlotRepository reservationSlotRepository;
    @MockitoBean private HospitalService hospitalService;

    private Long guardianMemberId;
    private Long staffMemberId;
    private Long reservationId;
    private Long slotId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            chatMessageRepository.deleteAll(chatMessageRepository.findByReservationIdOrderByCreatedAtAscIdAsc(
                    reservationId, org.springframework.data.domain.Pageable.unpaged()));
            reservationRepository.deleteById(reservationId);
        }
        if (slotId != null) {
            reservationSlotRepository.deleteById(slotId);
        }
        if (guardianMemberId != null) {
            memberRepository.deleteById(guardianMemberId);
        }
        if (staffMemberId != null) {
            memberRepository.deleteById(staffMemberId);
        }
    }

    @Test
    @DisplayName("보호자 탈퇴 뒤에도 병원은 과거 메시지를 탈퇴한 보호자 표시명으로 조회한다")
    void hospitalCanReadGuardianMessageAfterGuardianWithdrawal() {
        long hospitalId = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                hospitalId, now.plusDays(2), now.plusDays(2).plusMinutes(30)));
        slot.reserve();
        slotId = reservationSlotRepository.saveAndFlush(slot).getId();

        Member guardian = memberRepository.saveAndFlush(Member.createGuardian(
                "chat-withdrawal-guardian-" + hospitalId + "@example.com", "encoded", "탈퇴예정보호자"));
        guardianMemberId = guardian.getId();
        Member staff = Member.createGuardian(
                "chat-withdrawal-staff-" + hospitalId + "@example.com", "encoded", "스태프");
        ReflectionTestUtils.setField(staff, "role", MemberRole.HOSPITAL_STAFF);
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        staffMemberId = memberRepository.saveAndFlush(staff).getId();

        Reservation reservation = reservationRepository.saveAndFlush(Reservation.request(
                guardianMemberId, 1L, hospitalId, slotId, 1L,
                "초코", "DOG", now, slot.getStartAt()));
        reservationId = reservation.getId();
        given(hospitalService.getHospitalDetail(anyLong())).willReturn(new HospitalDetailResponse(
                hospitalId, "테스트동물병원", null, null, null, null, null, null,
                null, null, null, null, null, null, null, 0L, false));

        chatMessageService.send(reservationId, guardianPrincipal(), new ChatMessageSendRequest("탈퇴 전 메시지"));
        memberService.withdraw(guardianMemberId);

        var page = chatMessageService.getMessages(reservationId, staffPrincipal(), null, 10);

        assertThat(page.messages()).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo("탈퇴 전 메시지");
            assertThat(message.senderName()).isEqualTo("탈퇴한 보호자");
        });
    }

    private MemberPrincipal guardianPrincipal() {
        return new MemberPrincipal(guardianMemberId, "guardian@example.com", MemberRole.GUARDIAN.name());
    }

    private MemberPrincipal staffPrincipal() {
        return new MemberPrincipal(staffMemberId, "staff@example.com", MemberRole.HOSPITAL_STAFF.name());
    }
}
