package com.doctorpet.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.global.config.QuerydslConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
class ReservationSlotDdlIntegrationTest {

    @Autowired
    private ReservationSlotRepository reservationSlotRepository;

    @Test
    @DisplayName("같은 병원과 시작 시각의 슬롯은 종료 시각이 달라도 중복 저장할 수 없다")
    void duplicateHospitalAndStartAt_violatesUniqueConstraint() {
        long hospitalId = System.nanoTime();
        LocalDateTime startAt = LocalDateTime.now()
                .plusDays(3)
                .withNano(0);
        reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                hospitalId,
                startAt,
                startAt.plusMinutes(30)
        ));

        assertThatThrownBy(() ->
                reservationSlotRepository.saveAndFlush(ReservationSlot.create(
                        hospitalId,
                        startAt,
                        startAt.plusMinutes(60)
                ))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 병원과_조회_범위에_해당하는_슬롯을_시작_시각과_ID순으로_조회한다() {
        long hospitalId = System.nanoTime();
        long otherHospitalId = hospitalId + 1;
        LocalDateTime rangeStart = LocalDateTime.now()
                .plusDays(3)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);
        ReservationSlot first = reservationSlotRepository.save(
                ReservationSlot.create(
                        hospitalId,
                        rangeStart.plusHours(10),
                        rangeStart.plusHours(10).plusMinutes(30)
                )
        );
        ReservationSlot second = reservationSlotRepository.save(
                ReservationSlot.create(
                        hospitalId,
                        rangeStart.plusHours(11),
                        rangeStart.plusHours(11).plusMinutes(30)
                )
        );
        reservationSlotRepository.save(ReservationSlot.create(
                hospitalId,
                rangeStart.minusMinutes(30),
                rangeStart
        ));
        reservationSlotRepository.save(ReservationSlot.create(
                otherHospitalId,
                rangeStart.plusHours(9),
                rangeStart.plusHours(9).plusMinutes(30)
        ));
        reservationSlotRepository.flush();

        List<ReservationSlot> result = reservationSlotRepository
                .findSlotsInRange(
                        hospitalId,
                        rangeStart,
                        rangeStart.plusDays(1)
                );

        assertThat(result)
                .extracting(ReservationSlot::getId)
                .containsExactly(first.getId(), second.getId());
    }
}
