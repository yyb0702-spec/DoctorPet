package com.doctorpet.domain.reservation.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.global.config.QuerydslConfig;
import java.time.LocalDateTime;
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
}
