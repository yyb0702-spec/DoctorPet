package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.lock.ReservationLockStrategy;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

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
        reservation.confirm(requestedAt.plusHours(1));
        ReflectionTestUtils.setField(reservation, "id", 10L);
        return reservation;
    }
}
