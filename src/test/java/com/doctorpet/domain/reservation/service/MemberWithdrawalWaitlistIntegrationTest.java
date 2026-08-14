package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.member.service.MemberWithdrawalApplicationService;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.domain.reservation.repository.ReservationWaitlistRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Level 3 — 탈퇴 회원의 OFFERED 대기열이 슬롯을 점유하지 않도록 실제 MySQL 트랜잭션을 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3307/doctorpet?serverTimezone=Asia/Seoul&characterEncoding=UTF-8}",
        "spring.datasource.username=${SPRING_DATASOURCE_USERNAME:root}",
        "spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:root}",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host=localhost",
        "ai.gateway=fake",
        "payment.gateway=fake",
        "payment.billing-key.enc-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "jwt.secret=doctorpet-withdrawal-waitlist-test-secret-key-32-bytes-minimum",
        "member.email-verified-backfill.enabled=false",
        "notification.recipient-migration.enabled=false"
})
class MemberWithdrawalWaitlistIntegrationTest {

    @Autowired private MemberWithdrawalApplicationService memberWithdrawalApplicationService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ReservationSlotRepository reservationSlotRepository;
    @Autowired private ReservationWaitlistRepository reservationWaitlistRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long memberId;
    private Long slotId;

    @AfterEach
    void cleanUp() {
        if (slotId != null) {
            jdbcTemplate.update("delete from reservation_waitlists where slot_id = ?", slotId);
            jdbcTemplate.update("delete from reservation_slots where id = ?", slotId);
        }
        if (memberId != null) {
            jdbcTemplate.update("delete from members where id = ?", memberId);
        }
    }

    @Test
    @DisplayName("탈퇴하면 OFFERED 대기열이 CANCELED가 되고 슬롯은 다음 대기자 없을 때 OPEN으로 반환된다")
    void withdraw_cancelsOfferedWaitlistAndReleasesSlot() {
        Member member = memberRepository.saveAndFlush(Member.createGuardian(
                "withdraw-waitlist-" + System.nanoTime() + "@example.com", "encoded", "보호자"));
        memberId = member.getId();
        LocalDateTime startAt = LocalDateTime.now(SEOUL_ZONE_ID).plusDays(2).withNano(0);
        ReservationSlot slot = ReservationSlot.create(99L, startAt, startAt.plusMinutes(30));
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();
        ReservationWaitlist waitlist = ReservationWaitlist.waiting(memberId, slotId);
        LocalDateTime offeredAt = LocalDateTime.now(SEOUL_ZONE_ID).minusMinutes(1);
        waitlist.offer(offeredAt, offeredAt.plusMinutes(10));
        waitlist = reservationWaitlistRepository.saveAndFlush(waitlist);

        memberWithdrawalApplicationService.withdraw(memberId);

        assertThat(reservationWaitlistRepository.findById(waitlist.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationWaitlistStatus.CANCELED);
        assertThat(reservationSlotRepository.findById(slotId).orElseThrow().getStatus())
                .isEqualTo(ReservationSlotStatus.OPEN);
    }
}
