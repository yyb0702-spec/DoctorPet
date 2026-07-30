package com.doctorpet.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, QuerydslConfig.class})
class ReservationQueryRepositoryIntegrationTest {

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

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
            reservation.confirm(startAt.minusHours(1));
        }
        return reservationRepository.saveAndFlush(reservation);
    }
}
