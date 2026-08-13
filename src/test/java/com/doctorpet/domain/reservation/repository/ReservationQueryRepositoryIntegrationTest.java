package com.doctorpet.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.dto.query.ReservationHistoryAggregate;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class ReservationQueryRepositoryIntegrationTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("회원·상태·예약일 범위를 적용하고 슬롯 시작일 내림차순으로 조회한다")
    void findMyReservations_appliesFiltersAndReservedAtSort() {
        LocalDateTime july10 = LocalDateTime.of(2026, 7, 10, 10, 0);
        LocalDateTime july25 = LocalDateTime.of(2026, 7, 25, 14, 0);
        Reservation first = saveReservation(1L, july10, true);
        Reservation second = saveReservation(1L, july25, true);
        saveReservation(2L, july25.plusHours(1), true);

        Page<Reservation> result =
                reservationRepository.findMyReservations(
                        1L,
                        ReservationStatus.CONFIRMED,
                        LocalDateTime.of(2026, 7, 1, 0, 0),
                        LocalDateTime.of(2026, 8, 1, 0, 0),
                        Sort.Direction.DESC,
                        PageRequest.of(0, 20)
                );

        assertThat(result.getContent())
                .extracting(Reservation::getId)
                .containsExactly(second.getId(), first.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("상태와 날짜가 없으면 본인 예약만 예약일 오름차순으로 조회한다")
    void findMyReservations_withoutFilters_returnsOwnedReservations() {
        Reservation first = saveReservation(
                1L,
                LocalDateTime.of(2026, 7, 10, 10, 0),
                false
        );
        Reservation second = saveReservation(
                1L,
                LocalDateTime.of(2026, 7, 25, 14, 0),
                false
        );
        saveReservation(
                2L,
                LocalDateTime.of(2026, 7, 1, 9, 0),
                false
        );

        Page<Reservation> result =
                reservationRepository.findMyReservations(
                        1L,
                        null,
                        null,
                        null,
                        Sort.Direction.ASC,
                        PageRequest.of(0, 20)
                );

        assertThat(result.getContent())
                .extracting(Reservation::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("병원 운영 상태 전이는 소속 병원과 기대 상태를 조건으로 원자적으로 변경한다")
    void hospitalTransitions_requireHospitalAndExpectedStatus() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = saveReservation(
                1L,
                now.plusDays(1),
                false
        );

        assertThat(reservationRepository.approveIfRequested(
                reservation.getId(),
                999L,
                ReservationStatus.REQUESTED,
                ReservationStatus.CONFIRMED,
                now,
                now
        )).isZero();

        assertThat(reservationRepository.approveIfRequested(
                reservation.getId(),
                100L,
                ReservationStatus.REQUESTED,
                ReservationStatus.CONFIRMED,
                now,
                now
        )).isEqualTo(1);
        assertThat(reservationRepository.checkInIfAwaitingArrival(
                reservation.getId(),
                100L,
                List.of(ReservationStatus.CONFIRMED, ReservationStatus.NO_SHOW_PENDING),
                ReservationStatus.CHECKED_IN,
                now.minusMinutes(15),
                now
        )).isEqualTo(1);
        assertThat(reservationRepository.startTreatmentIfCheckedIn(
                reservation.getId(),
                100L,
                ReservationStatus.CHECKED_IN,
                ReservationStatus.IN_TREATMENT,
                now
        )).isEqualTo(1);
        assertThat(reservationRepository.completeTreatmentIfInTreatment(
                reservation.getId(),
                100L,
                ReservationStatus.IN_TREATMENT,
                ReservationStatus.TREATMENT_COMPLETED,
                now
        )).isEqualTo(1);

        entityManager.clear();
        assertThat(reservationRepository.findById(reservation.getId()))
                .get()
                .extracting(Reservation::getStatus)
                .isEqualTo(ReservationStatus.TREATMENT_COMPLETED);
    }

    @Test
    @DisplayName("병원 거절은 REQUESTED 상태에서만 사유와 함께 수행된다")
    void rejectIfRequested_updatesReasonAndRejectsOnlyRequested() {
        Reservation reservation = saveReservation(
                1L,
                LocalDateTime.of(2026, 7, 26, 14, 0),
                false
        );
        LocalDateTime now = LocalDateTime.now();

        assertThat(reservationRepository.rejectIfRequested(
                reservation.getId(),
                100L,
                ReservationStatus.REQUESTED,
                ReservationStatus.REJECTED,
                "직원 부족",
                now
        )).isEqualTo(1);

        entityManager.clear();
        Reservation rejected = reservationRepository
                .findById(reservation.getId())
                .orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(rejected.getRejectReason()).isEqualTo("직원 부족");

        assertThat(reservationRepository.rejectIfRequested(
                reservation.getId(),
                100L,
                ReservationStatus.REQUESTED,
                ReservationStatus.REJECTED,
                "다시 거절",
                now
        )).isZero();
    }

    @Test
    @DisplayName("병원 예약 목록은 요청한 상태만 조회한다")
    void findByHospitalIdAndStatus_returnsRequestedStatusOnly() {
        Reservation requested = saveReservation(
                1L,
                LocalDateTime.of(2026, 8, 1, 10, 0),
                false
        );
        saveReservation(
                2L,
                LocalDateTime.of(2026, 8, 1, 11, 0),
                true
        );

        Page<Reservation> result =
                reservationRepository.findByHospitalIdAndStatus(
                        100L,
                        ReservationStatus.REQUESTED,
                        PageRequest.of(0, 20)
                );

        assertThat(result.getContent())
                .extracting(Reservation::getId)
                .containsExactly(requested.getId());
    }

    @Test
    @DisplayName("병원 자동 취소 조회와 조건부 갱신은 시작 시각이 현재보다 미래인 예약만 허용한다")
    void hospitalCancellation_requiresFutureSlotStartAt() {
        LocalDateTime now = LocalDateTime.of(2099, 8, 13, 11, 30);
        Reservation future = saveReservationWithStatus(
                401L,
                now.plusSeconds(1),
                ReservationStatus.CONFIRMED
        );
        Reservation current = saveReservationWithStatus(
                402L,
                now,
                ReservationStatus.CONFIRMED
        );
        Reservation past = saveReservationWithStatus(
                403L,
                now.minusSeconds(1),
                ReservationStatus.CONFIRMED
        );

        List<Reservation> targets = reservationRepository
                .findAllCancelableByHospitalIdAndStatus(
                        100L,
                        ReservationStatus.CONFIRMED,
                        now
                );

        assertThat(targets)
                .extracting(Reservation::getId)
                .containsExactly(future.getId());
        assertThat(cancelByHospital(future.getId(), now)).isEqualTo(1);
        assertThat(cancelByHospital(current.getId(), now)).isZero();
        assertThat(cancelByHospital(past.getId(), now)).isZero();

        entityManager.clear();
        assertThat(reservationRepository.findById(future.getId()).orElseThrow()
                .getStatus()).isEqualTo(ReservationStatus.HOSPITAL_CANCELED);
        assertThat(reservationRepository.findById(current.getId()).orElseThrow()
                .getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservationRepository.findById(past.getId()).orElseThrow()
                .getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("회원별 예약 이력은 한 번의 집계 쿼리로 상태별 건수를 계산한다")
    void findHistoryAggregates_groupsCountsByMember() {
        long memberId = 301L;
        saveReservationWithStatus(
                memberId,
                LocalDateTime.of(2026, 8, 2, 10, 0),
                ReservationStatus.TREATMENT_COMPLETED
        );
        saveReservationWithStatus(
                memberId,
                LocalDateTime.of(2026, 8, 2, 11, 0),
                ReservationStatus.CANCELED
        );
        saveReservationWithStatus(
                memberId,
                LocalDateTime.of(2026, 8, 2, 12, 0),
                ReservationStatus.HOSPITAL_CANCELED
        );
        saveReservationWithStatus(
                memberId,
                LocalDateTime.of(2026, 8, 2, 12, 30),
                ReservationStatus.NO_SHOW
        );
        saveReservation(
                memberId,
                LocalDateTime.of(2026, 8, 2, 13, 0),
                false
        );

        List<ReservationHistoryAggregate> result =
                reservationRepository.findHistoryAggregates(
                        List.of(memberId),
                        ReservationStatus.TREATMENT_COMPLETED,
                        List.of(
                                ReservationStatus.CANCELED,
                                ReservationStatus.HOSPITAL_CANCELED
                        ),
                        ReservationStatus.NO_SHOW
                );

        assertThat(result).singleElement().satisfies(history -> {
            assertThat(history.memberId()).isEqualTo(memberId);
            assertThat(history.totalReservationCount()).isEqualTo(5L);
            assertThat(history.completedCount()).isEqualTo(1L);
            assertThat(history.cancelCount()).isEqualTo(2L);
            assertThat(history.noShowCount()).isEqualTo(1L);
        });
    }

    private Reservation saveReservationWithStatus(
            Long memberId,
            LocalDateTime startAt,
            ReservationStatus status
    ) {
        Reservation reservation = saveReservation(memberId, startAt, false);
        ReflectionTestUtils.setField(reservation, "status", status);
        return reservationRepository.saveAndFlush(reservation);
    }

    private int cancelByHospital(Long reservationId, LocalDateTime now) {
        return reservationRepository.cancelIfConfirmedByHospital(
                reservationId,
                100L,
                ReservationStatus.CONFIRMED,
                ReservationStatus.HOSPITAL_CANCELED,
                "공공데이터에서 병원 휴업이 확인되었습니다.",
                now,
                now,
                now
        );
    }

    private Reservation saveReservation(
            Long memberId,
            LocalDateTime startAt,
            boolean confirmed
    ) {
        ReservationSlot slot = reservationSlotRepository.saveAndFlush(
                ReservationSlot.create(
                        100L,
                        startAt,
                        startAt.plusMinutes(30)
                )
        );
        Reservation reservation = Reservation.request(
                memberId,
                10L,
                100L,
                slot.getId(),
                20L,
                "초코",
                "DOG",
                startAt.minusDays(1)
        );
        if (confirmed) {
            ReflectionTestUtils.setField(
                    reservation,
                    "status",
                    ReservationStatus.CONFIRMED
            );
            ReflectionTestUtils.setField(
                    reservation,
                    "confirmedAt",
                    startAt.minusHours(1)
            );
        }
        return reservationRepository.saveAndFlush(reservation);
    }
}
