package com.doctorpet.domain.reservation.service;
 
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
 
import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationSlotCreateCommand;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationSlotStatus;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.lock.ReservationLockStrategy;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
 
/**
 * hasActiveReservation()은 회원 탈퇴(Member 도메인)가 이 Service를 경유해서 호출하는
 * 조회 메서드다(SA §6-3, 부록A 확정). Reservation 도메인 자체 관점에서는 CONFIRMED·CHECKED_IN
 * 상태만 "활성"으로 취급하는지만 검증한다. 나머지 테스트는 예약 생성·취소·목록 조회 등
 * 도메인 자체 서비스 로직을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
 
    private static final Long MEMBER_ID = 1L;
    private static final Long PET_ID = 2L;
    private static final Long HOSPITAL_ID = 3L;
    private static final Long SLOT_ID = 4L;
    private static final Long PAYMENT_METHOD_ID = 5L;
 
    @Mock
    private ReservationRepository reservationRepository;
 
    @Mock
    private ReservationSlotRepository reservationSlotRepository;
 
    @Mock
    private ReservationLockStrategy reservationLockStrategy;
 
    private ReservationService reservationService;
 
    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                reservationRepository,
                reservationSlotRepository,
                reservationLockStrategy
        );
    }
 
    @Test
    @DisplayName("CONFIRMED·NO_SHOW_PENDING 또는 CHECKED_IN 예약이 있으면 true를 반환한다")
    void hasActiveReservation_true() {
        given(reservationRepository.existsByMemberIdAndStatusIn(
                eq(1L), eq(activeStatuses()))).willReturn(true);
 
        boolean result = reservationService.hasActiveReservation(1L);
 
        assertThat(result).isTrue();
    }
 
    @Test
    @DisplayName("활성 예약이 없으면 false를 반환한다(REQUESTED·CANCELED 등은 활성으로 안 본다)")
    void hasActiveReservation_false() {
        given(reservationRepository.existsByMemberIdAndStatusIn(
                eq(1L), eq(activeStatuses()))).willReturn(false);
 
        boolean result = reservationService.hasActiveReservation(1L);
 
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("예약된 슬롯이 없으면 영업 기준일의 열린 슬롯을 제거한다")
    void removeOpenSlotsIfNoReservation_removesOpenSlots() {
        LocalDate businessDate = LocalDate.of(2026, 8, 15);
        ReservationSlot first = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(9, 0),
                businessDate.atTime(9, 30),
                businessDate
        );
        ReservationSlot second = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(23, 30),
                businessDate.plusDays(1).atStartOfDay(),
                businessDate
        );
        given(reservationSlotRepository.findBusinessDateSlotsForUpdate(
                HOSPITAL_ID,
                businessDate
        )).willReturn(List.of(first, second));
        given(reservationSlotRepository.deleteOpenSlots(HOSPITAL_ID, businessDate))
                .willReturn(2);
        boolean removed = reservationService.removeOpenSlotsIfNoReservation(
                HOSPITAL_ID,
                businessDate
        );

        assertThat(removed).isTrue();
        verify(reservationSlotRepository).deleteOpenSlots(HOSPITAL_ID, businessDate);
    }

    @Test
    @DisplayName("예약된 슬롯이 하나라도 있으면 영업 기준일의 슬롯을 제거하지 않는다")
    void removeOpenSlotsIfNoReservation_keepsSlotsWhenReservedSlotExists() {
        LocalDate businessDate = LocalDate.of(2026, 8, 15);
        ReservationSlot reserved = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(9, 0),
                businessDate.atTime(9, 30),
                businessDate
        );
        reserved.reserve();
        given(reservationSlotRepository.findBusinessDateSlotsForUpdate(
                HOSPITAL_ID,
                businessDate
        )).willReturn(List.of(reserved));

        boolean removed = reservationService.removeOpenSlotsIfNoReservation(
                HOSPITAL_ID,
                businessDate
        );

        assertThat(reserved.getStatus()).isEqualTo(ReservationSlotStatus.RESERVED);
        assertThat(removed).isFalse();
        verify(reservationSlotRepository, never()).deleteOpenSlots(any(), any());
    }

    @Test
    @DisplayName("슬롯 생성은 같은 영업일에 이미 존재하는 시작 시각을 제외한다")
    void createOpenSlotsSkipsExistingStartTime() {
        LocalDate businessDate = LocalDate.of(2026, 8, 15);
        ReservationSlot existing = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(9, 0),
                businessDate.atTime(9, 30),
                businessDate
        );
        given(reservationSlotRepository.findBusinessDateSlots(
                HOSPITAL_ID,
                businessDate
        )).willReturn(List.of(existing));

        int created = reservationService.createOpenSlots(
                HOSPITAL_ID,
                businessDate,
                List.of(
                        new ReservationSlotCreateCommand(
                                businessDate.atTime(9, 0),
                                businessDate.atTime(9, 30)
                        ),
                        new ReservationSlotCreateCommand(
                                businessDate.atTime(9, 30),
                                businessDate.atTime(10, 0)
                        )
                )
        );

        assertThat(created).isEqualTo(1);
        var slots = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(reservationSlotRepository).saveAll(slots.capture());
        assertThat((List<ReservationSlot>) slots.getValue())
                .singleElement()
                .satisfies(slot -> {
                    assertThat(slot.getStartAt())
                            .isEqualTo(businessDate.atTime(9, 30));
                    assertThat(slot.getBusinessDate()).isEqualTo(businessDate);
                });
    }

    @Test
    @DisplayName("진료시간 변경 시 예약이 없는 영업일의 기존 슬롯을 모두 교체한다")
    void replaceOpenSlotsReplacesAllSlotsWhenNoReservationExists() {
        LocalDate businessDate = LocalDate.of(2026, 8, 15);
        ReservationSlot first = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(9, 0),
                businessDate.atTime(9, 30),
                businessDate
        );
        ReservationSlot second = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(10, 0),
                businessDate.atTime(10, 30),
                businessDate
        );
        given(reservationSlotRepository.findBusinessDateSlots(
                HOSPITAL_ID,
                businessDate
        )).willReturn(List.of(first, second));
        given(reservationSlotRepository.deleteOpenSlots(
                HOSPITAL_ID,
                businessDate
        )).willReturn(2);

        int created = reservationService.replaceOpenSlots(
                HOSPITAL_ID,
                businessDate,
                List.of(
                        new ReservationSlotCreateCommand(
                                businessDate.atTime(10, 0),
                                businessDate.atTime(10, 30)
                        ),
                        new ReservationSlotCreateCommand(
                                businessDate.atTime(11, 0),
                                businessDate.atTime(11, 30)
                        )
                )
        );

        assertThat(created).isEqualTo(2);
        verify(reservationSlotRepository).deleteOpenSlots(HOSPITAL_ID, businessDate);
        var slots = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(reservationSlotRepository).saveAll(slots.capture());
        assertThat((List<ReservationSlot>) slots.getValue())
                .extracting(ReservationSlot::getStartAt)
                .containsExactly(
                        businessDate.atTime(10, 0),
                        businessDate.atTime(11, 0)
                );
    }

    @Test
    @DisplayName("진료시간 변경 대상 영업일에 예약 슬롯이 있으면 슬롯을 교체하지 않는다")
    void replaceOpenSlotsRejectsWhenReservedSlotExists() {
        LocalDate businessDate = LocalDate.of(2026, 8, 15);
        ReservationSlot open = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(9, 0),
                businessDate.atTime(9, 30),
                businessDate
        );
        ReservationSlot reserved = ReservationSlot.create(
                HOSPITAL_ID,
                businessDate.atTime(10, 0),
                businessDate.atTime(10, 30),
                businessDate
        );
        reserved.reserve();
        given(reservationSlotRepository.findBusinessDateSlots(
                HOSPITAL_ID,
                businessDate
        )).willReturn(List.of(open, reserved));
        given(reservationSlotRepository.deleteOpenSlots(
                HOSPITAL_ID,
                businessDate
        )).willReturn(1);

        assertThatThrownBy(() -> reservationService.replaceOpenSlots(
                HOSPITAL_ID,
                businessDate,
                List.of(new ReservationSlotCreateCommand(
                        businessDate.atTime(11, 0),
                        businessDate.atTime(11, 30)
                ))
        )).isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(com.doctorpet.domain.reservation.exception.SlotErrorCode.ALREADY_RESERVED);

        verify(reservationSlotRepository).deleteOpenSlots(HOSPITAL_ID, businessDate);
        verify(reservationSlotRepository, never()).saveAll(any());
    }
 
    @Test
    @DisplayName("예약 서비스는 전달받은 반려동물 스냅샷으로 예약을 생성한다")
    void request_usesSnapshotProvidedByApplicationService() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(2);
        ReservationSlot slot = slot(startAt);
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));
        given(reservationLockStrategy.reserve(SLOT_ID)).willReturn(slot);
        given(reservationRepository.save(any(Reservation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
 
        reservationService.request(
                MEMBER_ID,
                new ReservationRequest(PET_ID, SLOT_ID, PAYMENT_METHOD_ID),
                "초코",
                "DOG"
        );
 
        var captor = org.mockito.ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().getPetNameSnapshot()).isEqualTo("초코");
        assertThat(captor.getValue().getPetSpeciesSnapshot()).isEqualTo("DOG");
    }
 
    @Test
    @DisplayName("예약 요청 시간이 4시간 이내면 거부한다")
    void request_insideLeadTime_throwsError() {
        ReservationSlot slot = slot(LocalDateTime.now().plusHours(3));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));
 
        assertThatThrownBy(() -> reservationService.request(
                MEMBER_ID,
                new ReservationRequest(PET_ID, SLOT_ID, PAYMENT_METHOD_ID),
                "초코",
                "DOG"
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.LEAD_TIME_VIOLATION);
 
        verify(reservationLockStrategy, never()).reserve(any());
        verify(reservationRepository, never()).save(any());
    }
 
    @Test
    @DisplayName("예약 목록 조회는 QueryDSL 결과를 그대로 반환한다")
    void findMyReservations_returnsPage() {
        Reservation reservation = reservation(LocalDateTime.now());
        given(reservationRepository.findMyReservations(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(reservation)));
 
        var result = reservationService.findMyReservations(
                MEMBER_ID,
                new ReservationListCondition(
                        "CONFIRMED",
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 31),
                        0,
                        20,
                        "reservedAt,desc"
                )
        );
 
        assertThat(result.getContent()).containsExactly(reservation);
    }
 
    @Test
    @DisplayName("최대 페이지 크기를 초과하면 거부한다")
    void findMyReservations_overMaxSize_throwsValidationError() {
        assertThatThrownBy(() -> reservationService.findMyReservations(
                MEMBER_ID,
                new ReservationListCondition(
                        null, null, null, 0, 101, "reservedAt,desc"
                )
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }
 
    @ParameterizedTest
    @ValueSource(strings = {"createdAt,desc", "reservedAt,sideways", "reservedAt"})
    @DisplayName("지원하지 않는 정렬은 INVALID_SORT로 거부한다")
    void findMyReservations_invalidSort_throwsError(String sort) {
        assertThatThrownBy(() -> reservationService.findMyReservations(
                MEMBER_ID,
                new ReservationListCondition(null, null, null, 0, 20, sort)
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_SORT);
    }
 
    @Test
    @DisplayName("빈 페이지도 전체 페이징 개수를 유지한다")
    void findMyReservations_emptyPage_preservesMetadata() {
        Pageable pageable = Pageable.ofSize(20).withPage(3);
        given(reservationRepository.findMyReservations(
                any(), any(), any(), any(), any(), any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(), pageable, 25));
 
        var result = reservationService.findMyReservations(
                MEMBER_ID,
                new ReservationListCondition(null, null, null, 3, 20,
                        "reservedAt,desc")
        );
 
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(25);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }
 
    @Test
    @DisplayName("취소는 상세 조회와 동일하게 타인 예약에 FORBIDDEN을 반환한다")
    void cancel_notOwner_returnsForbidden() {
        Reservation reservation = reservation(LocalDateTime.now().plusDays(1));
        given(reservationRepository.findById(10L))
                .willReturn(Optional.of(reservation));
 
        assertThatThrownBy(() -> reservationService.cancel(99L, 10L))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.FORBIDDEN);
 
        verify(reservationRepository, never()).cancelIfAllowed(
                any(), any(), any(), any(), any(), any()
        );
    }
 
    private Collection<ReservationStatus> activeStatuses() {
        return List.of(
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW_PENDING,
                ReservationStatus.CHECKED_IN
        );
    }
 
    private ReservationSlot slot(LocalDateTime startAt) {
        ReservationSlot slot = ReservationSlot.create(
                HOSPITAL_ID,
                startAt,
                startAt.plusMinutes(30)
        );
        ReflectionTestUtils.setField(slot, "id", SLOT_ID);
        return slot;
    }
 
    private Reservation reservation(LocalDateTime requestedAt) {
        Reservation reservation = Reservation.request(
                MEMBER_ID,
                PET_ID,
                HOSPITAL_ID,
                SLOT_ID,
                PAYMENT_METHOD_ID,
                "초코",
                "DOG",
                requestedAt
        );
        ReflectionTestUtils.setField(
                reservation,
                "status",
                ReservationStatus.CONFIRMED
        );
        ReflectionTestUtils.setField(
                reservation,
                "confirmedAt",
                requestedAt.plusHours(1)
        );
        ReflectionTestUtils.setField(reservation, "id", 10L);
        return reservation;
    }
}
