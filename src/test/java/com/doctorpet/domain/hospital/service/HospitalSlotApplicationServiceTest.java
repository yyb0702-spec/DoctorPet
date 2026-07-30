package com.doctorpet.domain.hospital.service;

import com.doctorpet.domain.hospital.dto.response.HospitalSlotAvailabilityStatus;
import com.doctorpet.domain.hospital.dto.response.HospitalSlotLookupResponse;
import com.doctorpet.domain.reservation.dto.query.ReservationSlotQueryResult;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.service.ReservationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HospitalSlotApplicationServiceTest {

    private static final Long HOSPITAL_ID = 1L;
    private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 8, 1, 6, 0);

    @Mock
    private HospitalService hospitalService;

    @Mock
    private ReservationService reservationService;

    private HospitalSlotApplicationService applicationService;

    @BeforeEach
    void setUp() {
        applicationService = new HospitalSlotApplicationService(
                hospitalService,
                reservationService
        );
    }

    @Test
    void 운영중인_제휴_병원이_아니면_빈_목록을_반환한다() {
        LocalDate selectedDate = NOW.toLocalDate();
        given(hospitalService.isReservationSlotLookupAvailable(HOSPITAL_ID))
                .willReturn(false);

        HospitalSlotLookupResponse response =
                applicationService.getHospitalSlots(
                        HOSPITAL_ID,
                        selectedDate
                );

        assertThat(response.selectedDate()).isEqualTo(selectedDate);
        assertThat(response.dateAvailabilities()).isEmpty();
        assertThat(response.slots()).isEmpty();
        verifyNoInteractions(reservationService);
    }

    @Test
    void 슬롯_상태와_4시간_마감_기준을_응답_상태로_변환한다() {
        LocalDate selectedDate = NOW.toLocalDate();
        List<ReservationSlotQueryResult> slots = List.of(
                slot(1L, NOW.plusHours(3), ReservationSlotStatus.OPEN),
                slot(2L, NOW.plusHours(4), ReservationSlotStatus.OPEN),
                slot(3L, NOW.plusHours(5), ReservationSlotStatus.RESERVED)
        );
        given(hospitalService.isReservationSlotLookupAvailable(HOSPITAL_ID))
                .willReturn(true);
        given(reservationService.findSlots(
                HOSPITAL_ID,
                selectedDate.atStartOfDay(),
                selectedDate.plusDays(14).atStartOfDay()
        )).willReturn(slots);

        HospitalSlotLookupResponse response = getHospitalSlotsAt(
                selectedDate,
                NOW
        );

        assertThat(response.dateAvailabilities()).hasSize(14);
        assertThat(response.dateAvailabilities().get(0)
                .reservationAvailable()).isTrue();
        assertThat(response.slots())
                .extracting("availabilityStatus")
                .containsExactly(
                        HospitalSlotAvailabilityStatus.LEAD_TIME_CLOSED,
                        HospitalSlotAvailabilityStatus.AVAILABLE,
                        HospitalSlotAvailabilityStatus.RESERVED
                );
    }

    @Test
    void 조회_범위_밖의_날짜는_날짜별_가용성과_빈_슬롯을_반환한다() {
        LocalDate selectedDate = NOW.toLocalDate().plusDays(14);
        given(hospitalService.isReservationSlotLookupAvailable(HOSPITAL_ID))
                .willReturn(true);
        given(reservationService.findSlots(
                HOSPITAL_ID,
                NOW.toLocalDate().atStartOfDay(),
                NOW.toLocalDate().plusDays(14).atStartOfDay()
        )).willReturn(List.of());

        HospitalSlotLookupResponse response = getHospitalSlotsAt(
                selectedDate,
                NOW
        );

        assertThat(response.dateAvailabilities()).hasSize(14);
        assertThat(response.dateAvailabilities())
                .allMatch(availability ->
                        !availability.reservationAvailable());
        assertThat(response.slots()).isEmpty();
    }

    @Test
    void 자정을_넘긴_슬롯은_시작_날짜에_포함한다() {
        LocalDate today = NOW.toLocalDate();
        LocalDate selectedDate = today.plusDays(1);
        ReservationSlotQueryResult overnightSlot = new ReservationSlotQueryResult(
                1L,
                selectedDate.atTime(0, 30),
                selectedDate.atTime(1, 0),
                ReservationSlotStatus.OPEN
        );
        given(hospitalService.isReservationSlotLookupAvailable(HOSPITAL_ID))
                .willReturn(true);
        given(reservationService.findSlots(
                HOSPITAL_ID,
                today.atStartOfDay(),
                today.plusDays(14).atStartOfDay()
        )).willReturn(List.of(overnightSlot));

        HospitalSlotLookupResponse response = getHospitalSlotsAt(
                selectedDate,
                NOW
        );

        assertThat(response.slots())
                .singleElement()
                .extracting("slotId")
                .isEqualTo(1L);
    }

    private ReservationSlotQueryResult slot(
            Long id,
            LocalDateTime startAt,
            ReservationSlotStatus status
    ) {
        return new ReservationSlotQueryResult(
                id,
                startAt,
                startAt.plusMinutes(30),
                status
        );
    }

    private HospitalSlotLookupResponse getHospitalSlotsAt(
            LocalDate selectedDate,
            LocalDateTime now
    ) {
        try (MockedStatic<LocalDateTime> mockedDateTime =
                     mockStatic(
                             LocalDateTime.class,
                             CALLS_REAL_METHODS
                     )) {
            mockedDateTime.when(() -> LocalDateTime.now(SEOUL_ZONE_ID))
                    .thenReturn(now);
            return applicationService.getHospitalSlots(
                    HOSPITAL_ID,
                    selectedDate
            );
        }
    }
}
