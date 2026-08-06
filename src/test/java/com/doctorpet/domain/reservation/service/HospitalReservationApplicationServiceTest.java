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
import com.doctorpet.domain.reservation.entity.ReservationEvent;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.config.ReservationNoShowProperties;
import com.doctorpet.domain.reservation.entity.status.ReservationRejectReason;
import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationEventRepository;
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

    @Mock
    private ReservationEventRepository reservationEventRepository;

    private HospitalReservationApplicationService hospitalReservationService;

    @BeforeEach
    void setUp() {
        ReservationNoShowProperties noShowProperties = new ReservationNoShowProperties();
        noShowProperties.setGraceMinutes(10);
        noShowProperties.setPendingGraceMinutes(5);
        hospitalReservationService = new HospitalReservationApplicationService(
                memberService,
                reservationRepository,
                reservationSlotRepository,
                reservationEventRepository,
                noShowProperties
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
        Reservation reservation = reservationWithStatus(
                HOSPITAL_ID,
                ReservationStatus.CONFIRMED
        );
        LocalDateTime checkedInAt = LocalDateTime.now(SEOUL_ZONE_ID);
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot()));
        given(reservationRepository.checkInIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
        )).willReturn(1);
        given(reservationEventRepository
                .findFirstByReservation_IdAndEventTypeOrderByOccurredAtAsc(
                        RESERVATION_ID,
                        ReservationEventType.CHECKED_IN
                )).willReturn(Optional.of(ReservationEvent.create(
                        reservation,
                        ReservationEventType.CHECKED_IN,
                        "병원 직원 도착 확인",
                        STAFF_ID,
                        checkedInAt
                )));

        hospitalReservationService.checkIn(STAFF_ID, RESERVATION_ID);

        verify(reservationRepository).checkInIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("이미 처리된 예약은 체크인할 수 없다")
    void checkIn_whenUpdateIsZero_throwsInvalidStatus() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservationWithStatus(
                        HOSPITAL_ID,
                        ReservationStatus.CONFIRMED
                )));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot()));
        given(reservationRepository.checkInIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
        )).willReturn(0);
        given(reservationRepository.findByIdAndHospitalIdForUpdate(
                RESERVATION_ID,
                HOSPITAL_ID
        )).willReturn(Optional.of(reservationWithStatus(
                HOSPITAL_ID,
                ReservationStatus.NO_SHOW
        )));

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
        LocalDateTime requestedAt = LocalDateTime.now(SEOUL_ZONE_ID);
        LocalDateTime slotStartAt = requestedAt.plusHours(1);
        Reservation reservation = Reservation.request(
                1L,
                2L,
                HOSPITAL_ID,
                SLOT_ID,
                3L,
                "초코",
                "DOG",
                requestedAt,
                slotStartAt
        );
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        ReservationSlot slot = slot(slotStartAt);
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
    @DisplayName("예약 시각 15분이 지나면 체크인할 수 없다")
    void checkIn_afterGraceTime_throwsDeadlinePassed() {
        Reservation reservation = reservationWithStatus(
                HOSPITAL_ID,
                ReservationStatus.CONFIRMED
        );
        ReservationSlot slot = slot(LocalDateTime.now().minusMinutes(16));
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

        verify(reservationRepository, never()).checkInIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
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
    @DisplayName("예약 시작 후 자기 병원의 CONFIRMED 예약을 수동 노쇼 확정한다")
    void confirmNoShow_success() {
        Reservation reservation = reservationWithStatus(
                HOSPITAL_ID,
                ReservationStatus.CONFIRMED
        );
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot(
                        LocalDateTime.now(SEOUL_ZONE_ID).minusMinutes(1)
                )));
        given(reservationRepository.markNoShowIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
        )).willReturn(1);

        hospitalReservationService.confirmNoShow(
                STAFF_ID,
                RESERVATION_ID,
                "예약 시간 미방문"
        );

        verify(reservationEventRepository).appendIfAbsent(
                org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                org.mockito.ArgumentMatchers.eq(ReservationEventType.MANUAL_NO_SHOW.name()),
                org.mockito.ArgumentMatchers.eq("예약 시간 미방문"),
                org.mockito.ArgumentMatchers.eq(STAFF_ID),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        );
    }

    @Test
    @DisplayName("예약 시작 전에는 수동 노쇼를 확정할 수 없다")
    void confirmNoShow_beforeStart_throwsTooEarly() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservationWithStatus(
                        HOSPITAL_ID,
                        ReservationStatus.CONFIRMED
                )));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot(
                        LocalDateTime.now(SEOUL_ZONE_ID).plusMinutes(1)
                )));

        assertThatThrownBy(() -> hospitalReservationService.confirmNoShow(
                STAFF_ID,
                RESERVATION_ID,
                "미방문"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.NO_SHOW_TOO_EARLY);

        verify(reservationRepository, never()).markNoShowIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("노쇼 확정 사유가 없으면 요청을 거부한다")
    void confirmNoShow_withoutReason_throwsInvalidReason() {
        assertThatThrownBy(() -> hospitalReservationService.confirmNoShow(
                STAFF_ID,
                RESERVATION_ID,
                " "
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_NO_SHOW_REASON);

        verify(memberService, never()).getMyInfo(any());
    }

    @Test
    @DisplayName("자동 노쇼가 먼저 처리되어도 수동 확정 이력을 남긴다")
    void confirmNoShow_afterAutomaticNoShow_appendsManualHistory() {
        Reservation confirmed = reservationWithStatus(
                HOSPITAL_ID,
                ReservationStatus.CONFIRMED
        );
        Reservation noShow = reservationWithStatus(
                HOSPITAL_ID,
                ReservationStatus.NO_SHOW
        );
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(confirmed));
        given(reservationRepository.findByIdAndHospitalIdForUpdate(
                RESERVATION_ID,
                HOSPITAL_ID
        )).willReturn(Optional.of(noShow));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot(
                        LocalDateTime.now(SEOUL_ZONE_ID).minusMinutes(11)
                )));
        given(reservationRepository.markNoShowIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
        )).willReturn(0);

        hospitalReservationService.confirmNoShow(
                STAFF_ID,
                RESERVATION_ID,
                "직원이 현장 확인"
        );

        verify(reservationEventRepository).appendIfAbsent(
                org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                org.mockito.ArgumentMatchers.eq(ReservationEventType.MANUAL_NO_SHOW.name()),
                org.mockito.ArgumentMatchers.eq("직원이 현장 확인"),
                org.mockito.ArgumentMatchers.eq(STAFF_ID),
                any(LocalDateTime.class)
        );
    }

    @Test
    @DisplayName("CONFIRMED나 NO_SHOW가 아닌 예약은 수동 노쇼 확정할 수 없다")
    void confirmNoShow_invalidStatus_throwsInvalidStatus() {
        Reservation checkedIn = reservationWithStatus(
                HOSPITAL_ID,
                ReservationStatus.CHECKED_IN
        );
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(checkedIn));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot(
                        LocalDateTime.now(SEOUL_ZONE_ID).minusMinutes(11)
                )));
        given(reservationRepository.markNoShowIfAwaitingArrival(
                any(), any(), any(), any(), any(), any()
        )).willReturn(0);
        given(reservationRepository.findByIdAndHospitalIdForUpdate(
                RESERVATION_ID,
                HOSPITAL_ID
        )).willReturn(Optional.of(checkedIn));

        assertThatThrownBy(() -> hospitalReservationService.confirmNoShow(
                STAFF_ID,
                RESERVATION_ID,
                "미방문"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("다른 병원의 예약은 수동 노쇼 확정할 수 없다")
    void confirmNoShow_otherHospital_throwsNotOwnHospital() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservationWithStatus(
                        OTHER_HOSPITAL_ID,
                        ReservationStatus.CONFIRMED
                )));

        assertThatThrownBy(() -> hospitalReservationService.confirmNoShow(
                STAFF_ID,
                RESERVATION_ID,
                "미방문"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.NOT_OWN_HOSPITAL);
    }

    @Test
    @DisplayName("NO_SHOW 예약을 CHECKED_IN으로 정정하고 이력을 추가한다")
    void restoreNoShow_success() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservationWithStatus(
                        HOSPITAL_ID,
                        ReservationStatus.NO_SHOW
                )));
        given(reservationRepository.restoreNoShowIfNoShow(
                any(), any(), any(), any(), any()
        )).willReturn(1);

        hospitalReservationService.restoreNoShow(
                STAFF_ID,
                RESERVATION_ID,
                "늦게 도착해 접수 완료"
        );

        verify(reservationEventRepository).appendIfAbsent(
                org.mockito.ArgumentMatchers.eq(RESERVATION_ID),
                org.mockito.ArgumentMatchers.eq(ReservationEventType.NO_SHOW_CORRECTED.name()),
                org.mockito.ArgumentMatchers.eq("늦게 도착해 접수 완료"),
                org.mockito.ArgumentMatchers.eq(STAFF_ID),
                any(LocalDateTime.class)
        );
    }

    @Test
    @DisplayName("이미 정정된 예약은 다시 정정할 수 없다")
    void restoreNoShow_repeated_throwsInvalidStatus() {
        given(reservationRepository.findById(RESERVATION_ID))
                .willReturn(Optional.of(reservationWithStatus(
                        HOSPITAL_ID,
                        ReservationStatus.CHECKED_IN
                )));
        given(reservationRepository.restoreNoShowIfNoShow(
                any(), any(), any(), any(), any()
        )).willReturn(0);

        assertThatThrownBy(() -> hospitalReservationService.restoreNoShow(
                STAFF_ID,
                RESERVATION_ID,
                "중복 정정"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_STATUS);
    }

    @Test
    @DisplayName("노쇼 정정 사유가 없으면 요청을 거부한다")
    void restoreNoShow_withoutReason_throwsInvalidReason() {
        assertThatThrownBy(() -> hospitalReservationService.restoreNoShow(
                STAFF_ID,
                RESERVATION_ID,
                null
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_NO_SHOW_RESTORE_REASON);
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

    private Reservation reservationWithStatus(
            Long hospitalId,
            ReservationStatus status
    ) {
        Reservation reservation = reservation(hospitalId);
        ReflectionTestUtils.setField(reservation, "status", status);
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
