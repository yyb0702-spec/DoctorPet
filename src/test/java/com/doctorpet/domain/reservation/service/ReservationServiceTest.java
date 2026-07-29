package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSummaryResponse;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.service.PetService;
import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationDetailResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationPageResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationProgressStatus;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.lock.ReservationLockStrategy;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @Mock
    private MemberService memberService;

    @Mock
    private PetService petService;

    @Mock
    private PaymentMethodService paymentMethodService;

    @Mock
    private HospitalService hospitalService;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                reservationRepository,
                reservationSlotRepository,
                reservationLockStrategy,
                memberService,
                petService,
                paymentMethodService,
                hospitalService
        );
    }

    @Test
    @DisplayName("예약 요청은 소유한 활성 PetProfile에서 스냅샷을 생성한다")
    void request_usesServerPetSnapshot() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(2);
        ReservationSlot slot = slot(startAt);
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));
        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.of(petResponse()));
        given(paymentMethodService.isActiveAndOwnedBy(
                MEMBER_ID,
                PAYMENT_METHOD_ID
        )).willReturn(true);
        given(reservationLockStrategy.reserve(SLOT_ID)).willReturn(slot);
        given(reservationRepository.save(any(Reservation.class)))
                .willAnswer(invocation -> {
                    Reservation reservation = invocation.getArgument(0);
                    ReflectionTestUtils.setField(reservation, "id", 10L);
                    return reservation;
                });

        reservationService.request(
                MEMBER_ID,
                new ReservationRequest(
                        PET_ID,
                        SLOT_ID,
                        PAYMENT_METHOD_ID
                )
        );

        verify(memberService).assertActiveMember(MEMBER_ID);
        verify(reservationLockStrategy).reserve(SLOT_ID);
        org.mockito.ArgumentCaptor<Reservation> captor =
                org.mockito.ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().getPetNameSnapshot()).isEqualTo("초코");
        assertThat(captor.getValue().getPetSpeciesSnapshot()).isEqualTo("DOG");
    }

    @Test
    @DisplayName("소유한 활성 반려동물이 아니면 예약을 생성하지 않는다")
    void request_missingOwnedPet_throwsProfileRequired() {
        ReservationSlot slot = slot(LocalDateTime.now().plusDays(2));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));
        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.request(
                MEMBER_ID,
                new ReservationRequest(PET_ID, SLOT_ID, PAYMENT_METHOD_ID)
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.PROFILE_REQUIRED);

        verify(reservationLockStrategy, never()).reserve(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("본인 소유의 활성 결제수단이 아니면 예약을 생성하지 않는다")
    void request_invalidPaymentMethod_throwsPaymentMethodRequired() {
        ReservationSlot slot = slot(LocalDateTime.now().plusDays(2));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));
        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.of(petResponse()));
        given(paymentMethodService.isActiveAndOwnedBy(
                MEMBER_ID,
                PAYMENT_METHOD_ID
        )).willReturn(false);

        assertThatThrownBy(() -> reservationService.request(
                MEMBER_ID,
                new ReservationRequest(PET_ID, SLOT_ID, PAYMENT_METHOD_ID)
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.PAYMENT_METHOD_REQUIRED);

        verify(reservationLockStrategy, never()).reserve(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("내 예약 목록은 슬롯 예약일로 정렬된 페이징 응답을 반환한다")
    void getMyReservations_returnsMappedPage() {
        LocalDateTime startAt = LocalDateTime.of(2026, 7, 25, 14, 0);
        Reservation reservation = reservation(startAt.minusDays(1));
        ReservationSlot slot = slot(startAt);
        given(reservationRepository.findMyReservations(
                org.mockito.ArgumentMatchers.eq(MEMBER_ID),
                org.mockito.ArgumentMatchers.eq(ReservationStatus.CONFIRMED),
                org.mockito.ArgumentMatchers.eq(
                        LocalDate.of(2026, 7, 1).atStartOfDay()
                ),
                org.mockito.ArgumentMatchers.eq(
                        LocalDate.of(2026, 8, 1).atStartOfDay()
                ),
                org.mockito.ArgumentMatchers.eq(Sort.Direction.DESC),
                any(Pageable.class)
        )).willReturn(new PageImpl<>(List.of(reservation)));
        given(reservationSlotRepository.findAllById(List.of(SLOT_ID)))
                .willReturn(List.of(slot));
        given(hospitalService.getHospitalSummaries(List.of(HOSPITAL_ID)))
                .willReturn(List.of(new HospitalSummaryResponse(
                        HOSPITAL_ID,
                        "닥터펫 동물병원"
                )));

        ReservationPageResponse response =
                reservationService.getMyReservations(
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

        verify(memberService).assertActiveMember(MEMBER_ID);
        assertThat(response.content()).singleElement().satisfies(item -> {
            assertThat(item.reservationId()).isEqualTo(10L);
            assertThat(item.hospitalName()).isEqualTo("닥터펫 동물병원");
            assertThat(item.petName()).isEqualTo("초코");
            assertThat(item.reservedAt()).isEqualTo(startAt);
            assertThat(item.progressStatus())
                    .isEqualTo(ReservationProgressStatus.RESERVATION_CONFIRMED);
        });
    }

    @Test
    @DisplayName("내 예약 상세는 병원·반려동물 스냅샷·슬롯을 조합한다")
    void getMyReservation_returnsDetail() {
        LocalDateTime startAt = LocalDateTime.of(2026, 7, 25, 14, 0);
        Reservation reservation = reservation(startAt.minusDays(1));
        ReservationSlot slot = slot(startAt);
        HospitalDetailResponse hospital =
                org.mockito.Mockito.mock(HospitalDetailResponse.class);
        given(reservationRepository.findById(10L))
                .willReturn(Optional.of(reservation));
        given(reservationSlotRepository.findById(SLOT_ID))
                .willReturn(Optional.of(slot));
        given(hospitalService.getHospitalDetail(HOSPITAL_ID))
                .willReturn(hospital);
        given(hospital.hospitalId()).willReturn(HOSPITAL_ID);
        given(hospital.name()).willReturn("닥터펫 동물병원");
        given(hospital.address()).willReturn("서울특별시 중구");
        given(hospital.phoneNumber()).willReturn("02-1234-5678");

        ReservationDetailResponse response =
                reservationService.getMyReservation(MEMBER_ID, 10L);

        verify(memberService).assertActiveMember(MEMBER_ID);
        assertThat(response.reservationId()).isEqualTo(10L);
        assertThat(response.hospital().name()).isEqualTo("닥터펫 동물병원");
        assertThat(response.petSnapshot().name()).isEqualTo("초코");
        assertThat(response.slot().startAt()).isEqualTo(startAt);
    }

    @Test
    @DisplayName("다른 회원의 예약 상세 조회는 FORBIDDEN으로 거부한다")
    void getMyReservation_notOwner_throwsForbidden() {
        Reservation reservation = reservation(LocalDateTime.now());
        given(reservationRepository.findById(10L))
                .willReturn(Optional.of(reservation));

        assertThatThrownBy(() ->
                reservationService.getMyReservation(999L, 10L)
        )
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.FORBIDDEN);

        verify(reservationSlotRepository, never()).findById(any());
        verify(hospitalService, never()).getHospitalDetail(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "createdAt,desc",
            "reservedAt,sideways",
            "reservedAt"
    })
    @DisplayName("허용되지 않은 정렬 조건은 INVALID_SORT로 거부한다")
    void getMyReservations_invalidSort(String sort) {
        assertThatThrownBy(() -> reservationService.getMyReservations(
                MEMBER_ID,
                new ReservationListCondition(
                        null,
                        null,
                        null,
                        0,
                        20,
                        sort
                )
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_SORT);
    }

    @Test
    @DisplayName("지원하지 않는 상태 필터는 INVALID_FILTER_STATUS로 거부한다")
    void getMyReservations_invalidStatus() {
        assertThatThrownBy(() -> reservationService.getMyReservations(
                MEMBER_ID,
                new ReservationListCondition(
                        "UNKNOWN",
                        null,
                        null,
                        0,
                        20,
                        "reservedAt,desc"
                )
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_FILTER_STATUS);
    }

    @Test
    @DisplayName("조회 시작일이 종료일보다 늦으면 INVALID_DATE_RANGE로 거부한다")
    void getMyReservations_invalidDateRange() {
        assertThatThrownBy(() -> reservationService.getMyReservations(
                MEMBER_ID,
                new ReservationListCondition(
                        null,
                        LocalDate.of(2026, 7, 31),
                        LocalDate.of(2026, 7, 1),
                        0,
                        20,
                        "reservedAt,desc"
                )
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.INVALID_DATE_RANGE);
    }

    private PetResponse petResponse() {
        return new PetResponse(
                PET_ID,
                "초코",
                PetSpecies.DOG,
                5,
                new BigDecimal("4.80"),
                true
        );
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

    private ReservationSlot slot(LocalDateTime startAt) {
        ReservationSlot slot = ReservationSlot.create(
                HOSPITAL_ID,
                startAt,
                startAt.plusMinutes(30)
        );
        ReflectionTestUtils.setField(slot, "id", SLOT_ID);
        return slot;
    }
}
