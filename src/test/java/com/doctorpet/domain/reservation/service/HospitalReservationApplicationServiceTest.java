package com.doctorpet.domain.reservation.service;

import static com.doctorpet.global.time.TimePolicy.SEOUL_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
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
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HospitalReservationApplicationServiceTest {

    private static final Long STAFF_ID = 50L;
    private static final Long HOSPITAL_ID = 100L;
    private static final Long OTHER_HOSPITAL_ID = 200L;
    private static final Long RESERVATION_ID = 10L;
    private static final Long SLOT_ID = 20L;

    @Mock
    private MemberService memberService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSlotRepository reservationSlotRepository;

    private HospitalReservationApplicationService hospitalReservationService;

    @BeforeEach
    void setUp() {
        hospitalReservationService = new HospitalReservationApplicationService(
                memberService,
                reservationRepository,
                reservationSlotRepository
        );
        lenient().when(memberService.getMyInfo(STAFF_ID)).thenReturn(
                new MemberResponse(
                        STAFF_ID,
                        "staff@example.com",
                        "병원스태프",
                        MemberRole.HOSPITAL_STAFF,
                        HOSPITAL_ID
                )
        );
    }

    @Test
    @DisplayName("자기 병원의 REQUESTED 예약을 승인한다")
    void approve_success() {
        Reservation reservation = reservation(HOSPITAL_ID);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot()));
        given(reservationRepository.approveIfRequested(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).willReturn(1);

        hospitalReservationService.approve(STAFF_ID, RESERVATION_ID);

        verify(reservationRepository).approveIfRequested(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("다른 병원의 예약은 승인할 수 없다")
    void approve_otherHospital_throwsNotOwnHospital() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(OTHER_HOSPITAL_ID)));

        assertThatThrownBy(() -> hospitalReservationService.approve(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL);

        verify(reservationRepository, never()).approveIfRequested(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("다른 병원 슬롯과 연결된 예약은 승인할 수 없다")
    void approve_otherHospitalSlot_throwsNotOwnHospital() {
        Reservation reservation = reservation(HOSPITAL_ID);
        ReservationSlot otherHospitalSlot = slot(
                OTHER_HOSPITAL_ID,
                LocalDateTime.now(SEOUL_ZONE_ID).plusDays(1),
                true
        );
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(otherHospitalSlot));

        assertThatThrownBy(() -> hospitalReservationService.approve(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL);

        verify(reservationRepository, never()).approveIfRequested(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("RESERVED 상태가 아닌 슬롯의 예약은 승인할 수 없다")
    void approve_openSlot_throwsInvalidSlotStatus() {
        Reservation reservation = reservation(HOSPITAL_ID);
        ReservationSlot openSlot = slot(
                HOSPITAL_ID,
                LocalDateTime.now(SEOUL_ZONE_ID).plusDays(1),
                false
        );
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(openSlot));

        assertThatThrownBy(() -> hospitalReservationService.approve(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(SlotErrorCode.INVALID_STATUS);

        verify(reservationRepository, never()).approveIfRequested(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("거절 사유가 없으면 거절하지 않는다")
    void reject_withoutReason_throwsRequiredError() {
        assertThatThrownBy(() -> hospitalReservationService.reject(
                STAFF_ID,
                RESERVATION_ID,
                null
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.REJECT_REASON_REQUIRED);

        verify(memberService, never()).getMyInfo(any());
        verify(reservationRepository, never()).rejectIfRequested(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("자기 병원의 REQUESTED 예약을 거절하고 슬롯을 반환한다")
    void reject_success_opensSlot() {
        Reservation reservation = reservation(HOSPITAL_ID);
        ReservationSlot slot = slot();
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationRepository.rejectIfRequested(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).willReturn(1);
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));

        hospitalReservationService.reject(
                STAFF_ID,
                RESERVATION_ID,
                ReservationRejectReason.STAFF_SHORTAGE
        );

        assertThat(slot.getStatus()).isEqualTo(ReservationSlotStatus.OPEN);
        verify(reservationRepository).rejectIfRequested(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("이미 처리된 예약은 조건부 갱신 실패로 INVALID_STATUS를 반환한다")
    void approve_whenUpdateIsZero_throwsInvalidStatus() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(HOSPITAL_ID)));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot()));
        given(reservationRepository.approveIfRequested(
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).willReturn(0);

        assertThatThrownBy(() -> hospitalReservationService.approve(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("자기 병원의 CONFIRMED 예약을 체크인 처리한다")
    void checkIn_success() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(HOSPITAL_ID)));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot()));
        given(reservationRepository.checkInIfConfirmed(
                any(), any(), any(), any(), any()
        )).willReturn(1);

        hospitalReservationService.checkIn(STAFF_ID, RESERVATION_ID);

        verify(reservationRepository).checkInIfConfirmed(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("이미 처리된 예약은 체크인할 수 없다")
    void checkIn_whenUpdateIsZero_throwsInvalidStatus() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(HOSPITAL_ID)));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot()));
        given(reservationRepository.checkInIfConfirmed(
                any(), any(), any(), any(), any()
        )).willReturn(0);

        assertThatThrownBy(() -> hospitalReservationService.checkIn(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("요청 후 1시간이 지나면 예약을 승인할 수 없다")
    void approve_afterRequestDeadline_throwsDeadlinePassed() {
        Reservation reservation = reservation(
                HOSPITAL_ID,
                LocalDateTime.now().minusHours(1).minusMinutes(1)
        );
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot()));

        assertThatThrownBy(() -> hospitalReservationService.approve(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.APPROVAL_DEADLINE_PASSED);

        verify(reservationRepository, never()).approveIfRequested(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @ResourceLock("default-time-zone")
    @DisplayName("JVM 기본 시간대가 UTC여도 서울 기준 승인 마감을 적용한다")
    void approve_withUtcJvm_usesSeoulDeadline() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            LocalDateTime nowInSeoul = LocalDateTime.now(SEOUL_ZONE_ID);
            Reservation reservation = reservation(
                    HOSPITAL_ID,
                    nowInSeoul.minusHours(1).minusMinutes(1)
            );
            given(reservationRepository.findById(RESERVATION_ID))
                    .willReturn(Optional.of(reservation));
            given(reservationSlotRepository.findById(SLOT_ID))
                    .willReturn(Optional.of(slot(
                            nowInSeoul.plusDays(1)
                    )));

            assertThatThrownBy(() -> hospitalReservationService.approve(
                    STAFF_ID,
                    RESERVATION_ID
            ))
                    .isInstanceOf(ServiceException.class)
                    .extracting("errorCode")
                    .isEqualTo(ReservationErrorCode.APPROVAL_DEADLINE_PASSED);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }

        verify(reservationRepository, never()).approveIfRequested(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("예약 시각 2시간 전이 지나면 요청 후 1시간 이내여도 승인할 수 없다")
    void approve_afterSlotDeadline_throwsDeadlinePassed() {
        Reservation reservation = reservation(HOSPITAL_ID);
        ReservationSlot slot = slot(LocalDateTime.now().plusHours(1));
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));

        assertThatThrownBy(() -> hospitalReservationService.approve(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.APPROVAL_DEADLINE_PASSED);
    }

    @Test
    @DisplayName("예약 시각 10분이 지나면 체크인할 수 없다")
    void checkIn_afterGraceTime_throwsDeadlinePassed() {
        Reservation reservation = reservation(HOSPITAL_ID);
        ReservationSlot slot = slot(LocalDateTime.now().minusMinutes(11));
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));

        assertThatThrownBy(() -> hospitalReservationService.checkIn(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.CHECK_IN_DEADLINE_PASSED);

        verify(reservationRepository, never()).checkInIfConfirmed(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("CHECKED_IN 예약의 진료를 시작한다")
    void startTreatment_success() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(HOSPITAL_ID)));
        given(reservationRepository.startTreatmentIfCheckedIn(
                any(), any(), any(), any(), any()
        )).willReturn(1);

        hospitalReservationService.startTreatment(STAFF_ID, RESERVATION_ID);

        verify(reservationRepository).startTreatmentIfCheckedIn(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("체크인되지 않은 예약은 진료를 시작할 수 없다")
    void startTreatment_whenUpdateIsZero_throwsInvalidStatus() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(HOSPITAL_ID)));
        given(reservationRepository.startTreatmentIfCheckedIn(
                any(), any(), any(), any(), any()
        )).willReturn(0);

        assertThatThrownBy(() -> hospitalReservationService.startTreatment(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("IN_TREATMENT 예약을 진료 완료 처리한다")
    void completeTreatment_success() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(HOSPITAL_ID)));
        given(reservationRepository.completeTreatmentIfInTreatment(
                any(), any(), any(), any(), any()
        )).willReturn(1);

        hospitalReservationService.completeTreatment(STAFF_ID, RESERVATION_ID);

        verify(reservationRepository).completeTreatmentIfInTreatment(
                any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("진료 중이 아닌 예약은 진료 완료할 수 없다")
    void completeTreatment_whenUpdateIsZero_throwsInvalidStatus() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation(HOSPITAL_ID)));
        given(reservationRepository.completeTreatmentIfInTreatment(
                any(), any(), any(), any(), any()
        )).willReturn(0);

        assertThatThrownBy(() -> hospitalReservationService.completeTreatment(
                STAFF_ID,
                RESERVATION_ID
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("자기 병원의 예약 요청 목록과 예약자 이력을 조회한다")
    void findHospitalReservations_success() {
        Reservation reservation = reservation(HOSPITAL_ID);
        ReservationSlot slot = slot();
        given(reservationRepository.findByHospitalIdAndStatus(
                any(),
                any(),
                any(Pageable.class)
        ))
                .willReturn(new PageImpl<>(List.of(reservation)));
        given(reservationSlotRepository.findAllById(any()))
                .willReturn(List.of(slot));
        given(reservationRepository.findHistoryAggregates(
                any(), any(), any(), any()
        )).willReturn(List.of());

        Page<?> result = hospitalReservationService.findHospitalReservations(
                STAFF_ID, "REQUESTED", 0, 20
        );

        assertThat(result.getContent()).hasSize(1);
        verify(reservationRepository).findByHospitalIdAndStatus(
                org.mockito.ArgumentMatchers.eq(HOSPITAL_ID),
                org.mockito.ArgumentMatchers.eq(ReservationStatus.REQUESTED),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        );
    }

    private Reservation reservation(Long hospitalId) {
        return reservation(hospitalId, LocalDateTime.now(SEOUL_ZONE_ID));
    }

    private Reservation reservation(
            Long hospitalId,
            LocalDateTime requestedAt
    ) {
        Reservation reservation = Reservation.request(
                1L,
                2L,
                hospitalId,
                SLOT_ID,
                3L,
                "초코",
                "DOG",
                requestedAt
        );
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        return reservation;
    }

    private ReservationSlot slot() {
        return slot(LocalDateTime.now(SEOUL_ZONE_ID).plusDays(1));
    }

    private ReservationSlot slot(LocalDateTime startAt) {
        return slot(HOSPITAL_ID, startAt, true);
    }

    private ReservationSlot slot(
            Long hospitalId,
            LocalDateTime startAt,
            boolean reserved
    ) {
        ReservationSlot slot = ReservationSlot.create(
                hospitalId,
                startAt,
                startAt.plusMinutes(30)
        );
        ReflectionTestUtils.setField(slot, "id", SLOT_ID);
        if (reserved) {
            slot.reserve();
        }
        return slot;
    }
}
