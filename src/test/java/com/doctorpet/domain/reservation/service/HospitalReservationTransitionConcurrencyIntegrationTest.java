package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.hospital.entity.BusinessStatus;
import com.doctorpet.domain.hospital.entity.Hospital;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.repository.HospitalRepository;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "payment.gateway=fake",
        "payment.billing-key.enc-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "mail.provider=fake",
        "mail.verification.base-url=http://localhost/verify-email",
        "mail.password-reset.base-url=http://localhost/reset-password",
        "member.email-verified-backfill.enabled=false"
})
class HospitalReservationTransitionConcurrencyIntegrationTest {

    @Autowired
    private HospitalReservationApplicationService hospitalReservationService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long reservationId;
    private Long slotId;
    private Long staffMemberId;
    private Long hospitalId;

    @AfterEach
    void cleanUp() {
        if (reservationId != null) {
            jdbcTemplate.update(
                    "delete from notifications where resource_type = 'RESERVATION' and resource_id = ?",
                    reservationId
            );
            jdbcTemplate.update(
                    "delete from reservation_events where reservation_id = ?",
                    reservationId
            );
            jdbcTemplate.update(
                    "delete from reservations where id = ?",
                    reservationId
            );
        }
        if (slotId != null) {
            jdbcTemplate.update(
                    "delete from reservation_slots where id = ?",
                    slotId
            );
        }
        if (staffMemberId != null) {
            jdbcTemplate.update(
                    "delete from members where id = ?",
                    staffMemberId
            );
        }
        if (hospitalId != null) {
            jdbcTemplate.update(
                    "delete from hospitals where id = ?",
                    hospitalId
            );
        }
    }

    @Test
    @DisplayName("동일 REQUESTED 예약의 승인과 거절은 정확히 하나만 성공한다")
    void approveAndReject_onlyOneSucceeds() throws InterruptedException {
        TestReservation data = saveRequestedReservation();
        RaceResult result = runRace(
                () -> hospitalReservationService.approve(
                        data.staffMemberId(),
                        data.reservationId()
                ),
                () -> hospitalReservationService.reject(
                        data.staffMemberId(),
                        data.reservationId(),
                        ReservationRejectReason.STAFF_SHORTAGE
                )
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.unexpectedErrors()).isEmpty();

        ReservationStatus status = findReservationStatus(data.reservationId());
        ReservationSlotStatus slotStatus = findSlotStatus(data.slotId());
        assertThat(status).isIn(
                ReservationStatus.CONFIRMED,
                ReservationStatus.REJECTED
        );
        assertThat(slotStatus).isEqualTo(
                status == ReservationStatus.CONFIRMED
                        ? ReservationSlotStatus.RESERVED
                        : ReservationSlotStatus.OPEN
        );
    }

    @Test
    @DisplayName("동일 REQUESTED 예약의 사용자 취소와 병원 거절은 정확히 하나만 성공하고 슬롯을 반환한다")
    void cancelAndReject_onlyOneSucceedsAndOpensSlot()
            throws InterruptedException {
        TestReservation data = saveRequestedReservation();
        RaceResult result = runRace(
                () -> reservationService.cancel(
                        data.guardianMemberId(),
                        data.reservationId()
                ),
                () -> hospitalReservationService.reject(
                        data.staffMemberId(),
                        data.reservationId(),
                        ReservationRejectReason.TREATMENT_UNAVAILABLE
                )
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.unexpectedErrors()).isEmpty();
        assertThat(findReservationStatus(data.reservationId())).isIn(
                ReservationStatus.CANCELED,
                ReservationStatus.REJECTED
        );
        assertThat(findSlotStatus(data.slotId()))
                .isEqualTo(ReservationSlotStatus.OPEN);
    }

    @Test
    @DisplayName("동일 CONFIRMED 예약의 병원 취소와 체크인은 정확히 하나만 성공하고 최종 슬롯 상태가 일치한다")
    void hospitalCancelAndCheckIn_onlyOneSucceedsWithConsistentSlot()
            throws InterruptedException {
        TestReservation data = saveConfirmedReservation();
        RaceResult result = runRace(
                () -> hospitalReservationService.cancelConfirmedByHospital(
                        data.staffMemberId(),
                        data.reservationId(),
                        "응급수술로 진료 불가"
                ),
                () -> hospitalReservationService.checkIn(
                        data.staffMemberId(),
                        data.reservationId()
                )
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.unexpectedErrors()).isEmpty();

        ReservationStatus status = findReservationStatus(data.reservationId());
        ReservationSlotStatus slotStatus = findSlotStatus(data.slotId());
        assertThat(status).isIn(
                ReservationStatus.HOSPITAL_CANCELED,
                ReservationStatus.CHECKED_IN
        );
        assertThat(slotStatus).isEqualTo(
                status == ReservationStatus.HOSPITAL_CANCELED
                        ? ReservationSlotStatus.OPEN
                        : ReservationSlotStatus.RESERVED
        );
    }

    @Test
    @DisplayName("동일 CONFIRMED 예약의 병원 취소와 보호자 취소는 정확히 하나만 성공하고 슬롯을 반환한다")
    void hospitalCancelAndGuardianCancel_onlyOneSucceedsAndOpensSlot()
            throws InterruptedException {
        TestReservation data = saveConfirmedReservation();
        RaceResult result = runRace(
                () -> hospitalReservationService.cancelConfirmedByHospital(
                        data.staffMemberId(),
                        data.reservationId(),
                        "응급수술로 진료 불가"
                ),
                () -> reservationService.cancel(
                        data.guardianMemberId(),
                        data.reservationId()
                )
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.unexpectedErrors()).isEmpty();
        assertThat(findReservationStatus(data.reservationId())).isIn(
                ReservationStatus.HOSPITAL_CANCELED,
                ReservationStatus.CANCELED
        );
        assertThat(findSlotStatus(data.slotId())).isEqualTo(ReservationSlotStatus.OPEN);
    }

    @Test
    @DisplayName("휴·폐업 자동 취소와 체크인이 경합하면 최종 예약·슬롯 상태가 일치한다")
    void businessStatusCancellationAndCheckInLeaveConsistentState()
            throws InterruptedException {
        TestReservation data = saveConfirmedReservation();
        RaceResult result = runRace(
                () -> hospitalReservationService
                        .cancelConfirmedByBusinessStatusChange(
                                reservationRepository.findById(data.reservationId())
                                        .orElseThrow()
                                        .getHospitalId(),
                                "공공데이터에서 병원 휴업이 확인되었습니다."
                        ),
                () -> hospitalReservationService.checkIn(
                        data.staffMemberId(),
                        data.reservationId()
                )
        );

        assertThat(result.unexpectedErrors()).isEmpty();
        ReservationStatus status = findReservationStatus(data.reservationId());
        assertThat(status).isIn(
                ReservationStatus.HOSPITAL_CANCELED,
                ReservationStatus.CHECKED_IN
        );
        assertThat(findSlotStatus(data.slotId())).isEqualTo(
                status == ReservationStatus.HOSPITAL_CANCELED
                        ? ReservationSlotStatus.OPEN
                        : ReservationSlotStatus.RESERVED
        );
    }

    @Test
    @DisplayName("예약 승인과 휴업 전환이 경합해도 휴업 병원에 확정 예약이 남지 않는다")
    void approvalAndBusinessStatusChangeNeverLeaveConfirmedReservation()
            throws InterruptedException {
        TestReservation data = saveRequestedReservation();

        RaceResult result = runRace(
                () -> hospitalReservationService.approve(
                        data.staffMemberId(),
                        data.reservationId()
                ),
                () -> transactionTemplate.executeWithoutResult(status -> {
                    Hospital hospital = hospitalRepository
                            .findByIdForUpdate(hospitalId)
                            .orElseThrow();
                    ReflectionTestUtils.setField(
                            hospital,
                            "businessStatus",
                            BusinessStatus.CLOSED_TEMP
                    );
                    hospitalReservationService.cancelConfirmedByBusinessStatusChange(
                            hospitalId,
                            "공공데이터에서 병원 휴업이 확인되었습니다."
                    );
                })
        );

        assertThat(result.successCount()).isBetween(1, 2);
        assertThat(result.unexpectedErrors()).isEmpty();
        assertThat(hospitalRepository.findById(hospitalId).orElseThrow()
                .getBusinessStatus()).isEqualTo(BusinessStatus.CLOSED_TEMP);

        ReservationStatus reservationStatus = findReservationStatus(
                data.reservationId()
        );
        assertThat(reservationStatus).isIn(
                ReservationStatus.REQUESTED,
                ReservationStatus.HOSPITAL_CANCELED
        );
        assertThat(findSlotStatus(data.slotId())).isEqualTo(
                reservationStatus == ReservationStatus.HOSPITAL_CANCELED
                        ? ReservationSlotStatus.OPEN
                        : ReservationSlotStatus.RESERVED
        );
    }

    private TestReservation saveRequestedReservation() {
        Hospital hospital = Hospital.createFromPublicData(
                "transition-" + System.nanoTime(),
                "test-local-gov",
                "동시성 테스트 병원",
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.now(),
                BusinessStatus.OPEN,
                null,
                null,
                LocalDateTime.now()
        );
        hospital.markAsPartner();
        hospital = hospitalRepository.saveAndFlush(hospital);
        hospitalId = hospital.getId();
        long guardianMemberId = hospitalId + 1;
        LocalDateTime now = LocalDateTime.now(SEOUL_ZONE_ID);
        LocalDateTime startAt = now.plusDays(2).withNano(0);

        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                startAt,
                startAt.plusMinutes(30)
        );
        slot.reserve();
        slot = reservationSlotRepository.saveAndFlush(slot);
        slotId = slot.getId();

        Reservation reservation = Reservation.request(
                guardianMemberId,
                1L,
                hospitalId,
                slot.getId(),
                1L,
                "초코",
                "DOG",
                now
        );
        reservation = reservationRepository.saveAndFlush(reservation);
        reservationId = reservation.getId();

        Member staff = Member.createGuardian(
                "hospital-transition-" + System.nanoTime() + "@example.com",
                "encoded-password",
                "병원스태프"
        );
        ReflectionTestUtils.setField(
                staff,
                "role",
                MemberRole.HOSPITAL_STAFF
        );
        ReflectionTestUtils.setField(staff, "hospitalId", hospitalId);
        staff = memberRepository.saveAndFlush(staff);
        staffMemberId = staff.getId();

        return new TestReservation(
                guardianMemberId,
                staff.getId(),
                reservation.getId(),
                slot.getId()
        );
    }

    private TestReservation saveConfirmedReservation() {
        TestReservation data = saveRequestedReservation();
        jdbcTemplate.update(
                "update reservations set status = 'CONFIRMED' where id = ?",
                data.reservationId()
        );
        return data;
    }

    private RaceResult runRace(Runnable first, Runnable second)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        for (Runnable task : List.of(first, second)) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                    successCount.incrementAndGet();
                } catch (ServiceException exception) {
                    if (!isExpectedRaceLoss(exception)) {
                        unexpectedErrors.add(exception);
                    }
                } catch (Throwable throwable) {
                    unexpectedErrors.add(throwable);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        return new RaceResult(successCount.get(), unexpectedErrors);
    }

    private boolean isExpectedRaceLoss(ServiceException exception) {
        return exception.getErrorCode() == ReservationErrorCode.INVALID_STATUS
                || exception.getErrorCode() == SlotErrorCode.INVALID_STATUS
                || exception.getErrorCode()
                == HospitalErrorCode.HOSPITAL_RESERVATION_APPROVAL_NOT_AVAILABLE;
    }

    private ReservationStatus findReservationStatus(Long id) {
        return reservationRepository.findById(id)
                .orElseThrow()
                .getStatus();
    }

    private ReservationSlotStatus findSlotStatus(Long id) {
        return reservationSlotRepository.findById(id)
                .orElseThrow()
                .getStatus();
    }

    private record TestReservation(
            Long guardianMemberId,
            Long staffMemberId,
            Long reservationId,
            Long slotId
    ) {
    }

    private record RaceResult(
            int successCount,
            List<Throwable> unexpectedErrors
    ) {
    }
}
