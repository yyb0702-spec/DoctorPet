package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import com.doctorpet.domain.notification.service.NotificationService;
import com.doctorpet.domain.payment.entity.PaymentMethod;
import com.doctorpet.domain.payment.repository.PaymentMethodRepository;
import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.repository.PetProfileRepository;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Level 3 — 보호자가 새 예약을 요청하면 병원 수신(HOSPITAL) 알림이 실제 {@code notifications} 테이블에
 * 저장되는지, 그리고 그 저장이 예약 생성 트랜잭션에 참여해 함께 롤백되는지 실제 MySQL로 검증한다(#166).
 *
 * <p>#162가 SSE 전달 경로를 열었지만 발행 지점이 없어 병원 알림 행 자체가 만들어지지 않던 갭을 메운 변경이라,
 * 여기서는 "행이 실제로 생기는가"와 "수신자가 회원이 아니라 병원인가"를 고정한다. 저장 이후의 SSE fan-out은
 * {@code NotificationHospitalFanOutIntegrationTest}가 담당한다.
 *
 * <p>전체 컨텍스트(MySQL·Redis·env)가 필요하다 — 없으면 BLOCKED.
 */
@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class ReservationRequestHospitalNotificationIntegrationTest {

    private static final String EXPECTED_CONTENT = "새로운 예약 요청이 접수되었습니다.";

    @Autowired
    private ReservationApplicationService reservationApplicationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private PetProfileRepository petProfileRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 기본은 실제 저장 동작을 그대로 쓰고, 롤백 검증 테스트에서만 예외를 던지도록 스텁한다.
    @MockitoSpyBean
    private NotificationService notificationService;

    private Long createdSlotId;
    private Long createdPetId;
    private Long createdPaymentMethodId;
    private Long createdMemberId;
    private Long createdHospitalId;

    @AfterEach
    void cleanUp() {
        if (createdHospitalId != null) {
            jdbcTemplate.update(
                    "delete from notifications where recipient_type = 'HOSPITAL' and recipient_id = ?",
                    createdHospitalId);
        }
        if (createdSlotId != null) {
            jdbcTemplate.update("delete from reservations where slot_id = ?", createdSlotId);
            jdbcTemplate.update("delete from reservation_slots where id = ?", createdSlotId);
        }
        if (createdPetId != null) {
            jdbcTemplate.update("delete from pet_profiles where id = ?", createdPetId);
        }
        if (createdPaymentMethodId != null) {
            jdbcTemplate.update("delete from payment_methods where id = ?", createdPaymentMethodId);
        }
        if (createdMemberId != null) {
            jdbcTemplate.update("delete from members where id = ?", createdMemberId);
        }
        if (createdHospitalId != null) {
            hospitalRepository.deleteById(createdHospitalId);
        }
    }

    @Test
    @DisplayName("예약 요청 시 병원 수신 RESERVATION_REQUESTED 알림이 예약에 연결돼 1건 저장된다")
    void request_persistsHospitalRecipientNotification() {
        Fixture fixture = saveOpenSlotFixture();

        ReservationResponse reservation = reservationApplicationService.request(
                fixture.memberId(),
                new ReservationRequest(fixture.petId(), fixture.slotId(), fixture.paymentMethodId())
        );

        assertThat(notificationCount(reservation.reservationId())).isEqualTo(1);
        // 수신자가 병원이어야 한다 — 회원 수신으로 저장되면 병원 스태프 목록·SSE 어디에도 나타나지 않는다.
        assertThat(notificationColumn(reservation.reservationId(), "recipient_type", String.class))
                .isEqualTo(NotificationRecipientType.HOSPITAL.name());
        assertThat(notificationColumn(reservation.reservationId(), "recipient_id", Long.class))
                .isEqualTo(fixture.hospitalId());
        // 병원 수신 행의 member_id는 하위호환 컬럼이라 비어 있어야 한다(고도화 알림 3.10).
        assertThat(notificationColumn(reservation.reservationId(), "member_id", Long.class)).isNull();
        assertThat(notificationColumn(reservation.reservationId(), "type", String.class))
                .isEqualTo(NotificationType.RESERVATION_REQUESTED.name());
        assertThat(notificationColumn(reservation.reservationId(), "content", String.class))
                .isEqualTo(EXPECTED_CONTENT);
        assertThat(notificationColumn(reservation.reservationId(), "read_at", LocalDateTime.class)).isNull();
    }

    @Test
    @DisplayName("대기열 승급 수락으로 만든 예약도 병원 수신 알림을 1건 저장한다")
    void requestFromWaitlist_persistsHospitalRecipientNotification() {
        // 승급 제안 시점에 슬롯이 이미 점유(RESERVED)된 상태가 대기열 수락 경로의 전제다.
        Fixture fixture = saveReservedSlotFixture();

        ReservationResponse reservation = reservationApplicationService.requestFromWaitlist(
                fixture.memberId(),
                fixture.petId(),
                fixture.paymentMethodId(),
                fixture.slotId()
        );

        assertThat(notificationCount(reservation.reservationId())).isEqualTo(1);
        assertThat(notificationColumn(reservation.reservationId(), "recipient_type", String.class))
                .isEqualTo(NotificationRecipientType.HOSPITAL.name());
        assertThat(notificationColumn(reservation.reservationId(), "recipient_id", Long.class))
                .isEqualTo(fixture.hospitalId());
        assertThat(notificationColumn(reservation.reservationId(), "type", String.class))
                .isEqualTo(NotificationType.RESERVATION_REQUESTED.name());
    }

    @Test
    @DisplayName("알림 저장이 실패하면 예약 생성과 슬롯 점유까지 함께 롤백된다")
    void notificationFailure_rollsBackReservationAndSlot() {
        Fixture fixture = saveOpenSlotFixture();
        doThrow(new IllegalStateException("알림 저장 실패 시뮬레이션"))
                .when(notificationService)
                .create(any(NotificationRecipientType.class), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> reservationApplicationService.request(
                fixture.memberId(),
                new ReservationRequest(fixture.petId(), fixture.slotId(), fixture.paymentMethodId())
        )).isInstanceOf(IllegalStateException.class);

        // 알림 저장이 예약 생성과 같은 트랜잭션이므로 예약도 슬롯 점유도 남지 않아야 한다.
        assertThat(reservationRepository.countBySlotId(fixture.slotId())).isZero();
        assertThat(reservationSlotRepository.findById(fixture.slotId()).orElseThrow().getStatus())
                .isEqualTo(ReservationSlotStatus.OPEN);
        assertThat(hospitalNotificationCount(fixture.hospitalId())).isZero();
    }

    private Fixture saveOpenSlotFixture() {
        return saveFixture(false);
    }

    private Fixture saveReservedSlotFixture() {
        return saveFixture(true);
    }

    private Fixture saveFixture(boolean reserveSlot) {
        Hospital hospital = Hospital.createFromPublicData(
                "RESERVATION-REQUESTED-NOTIFICATION-" + System.nanoTime(),
                "TEST-LOCAL-GOV",
                "예약 요청 알림 테스트 병원",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                BusinessStatus.OPEN,
                null,
                null,
                null
        );
        hospital.markAsPartner();
        createdHospitalId = hospitalRepository.saveAndFlush(hospital).getId();

        LocalDateTime startAt = LocalDateTime.now().plusDays(2).withNano(0);
        ReservationSlot slot = ReservationSlot.create(
                createdHospitalId,
                startAt,
                startAt.plusMinutes(30)
        );
        if (reserveSlot) {
            slot.reserve();
        }
        createdSlotId = reservationSlotRepository.saveAndFlush(slot).getId();

        Member member = memberRepository.saveAndFlush(
                Member.createGuardian(
                        "reservation-requested-notification-" + System.nanoTime() + "@example.com",
                        "encoded-password",
                        "예약요청알림"
                )
        );
        createdMemberId = member.getId();

        createdPetId = petProfileRepository.saveAndFlush(
                PetProfile.create(
                        createdMemberId,
                        "초코",
                        PetSpecies.DOG,
                        3,
                        new BigDecimal("5.40"),
                        true
                )
        ).getId();

        createdPaymentMethodId = paymentMethodRepository.saveAndFlush(
                PaymentMethod.issue(createdMemberId, "v1:test-encrypted", "TEST", "1234")
        ).getId();

        return new Fixture(
                createdMemberId,
                createdPetId,
                createdPaymentMethodId,
                createdSlotId,
                createdHospitalId
        );
    }

    private long notificationCount(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications "
                        + "where resource_type = 'RESERVATION' and resource_id = ? and type = ?",
                Long.class,
                reservationId,
                NotificationType.RESERVATION_REQUESTED.name());
    }

    private long hospitalNotificationCount(Long hospitalId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications "
                        + "where recipient_type = 'HOSPITAL' and recipient_id = ?",
                Long.class,
                hospitalId);
    }

    private <T> T notificationColumn(Long reservationId, String column, Class<T> type) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from notifications "
                        + "where resource_type = 'RESERVATION' and resource_id = ? and type = ?",
                type,
                reservationId,
                NotificationType.RESERVATION_REQUESTED.name());
    }

    private record Fixture(
            Long memberId,
            Long petId,
            Long paymentMethodId,
            Long slotId,
            Long hospitalId
    ) {
    }
}
