package com.doctorpet.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.dto.response.HospitalSummaryResponse;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.pet.service.PetService;
import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationPaymentMethodUpdateRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationPageResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationApplicationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long PET_ID = 2L;
    private static final Long HOSPITAL_ID = 3L;
    private static final Long SLOT_ID = 4L;
    private static final Long PAYMENT_METHOD_ID = 5L;

    @Mock
    private ReservationService reservationService;

    @Mock
    private MemberService memberService;

    @Mock
    private PetService petService;

    @Mock
    private PaymentMethodService paymentMethodService;

    @Mock
    private PaymentQueryService paymentQueryService;

    @Mock
    private HospitalService hospitalService;

    private ReservationApplicationService applicationService;

    @BeforeEach
    void setUp() {
        applicationService = new ReservationApplicationService(
                reservationService,
                memberService,
                petService,
                paymentMethodService,
                paymentQueryService,
                hospitalService
        );
    }

    @Test
    @DisplayName("예약 요청은 활성 반려동물과 결제수단의 소유권을 확인한 뒤 위임한다")
    void request_validatesOwnershipAndDelegates() {
        ReservationRequest request = new ReservationRequest(
                PET_ID,
                SLOT_ID,
                PAYMENT_METHOD_ID
        );
        PetResponse pet = pet("초코", PetSpecies.DOG);
        ReservationResponse expected = new ReservationResponse(
                10L,
                PET_ID,
                HOSPITAL_ID,
                SLOT_ID,
                null,
                LocalDateTime.now()
        );

        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.of(pet));
        given(reservationService.findSlot(SLOT_ID)).willReturn(slot());
        given(paymentMethodService.isActiveAndOwnedBy(
                MEMBER_ID,
                PAYMENT_METHOD_ID
        )).willReturn(true);
        given(reservationService.request(
                eq(MEMBER_ID),
                eq(request),
                eq("초코"),
                eq("DOG")
        )).willReturn(expected);

        ReservationResponse result = applicationService.request(
                MEMBER_ID,
                request
        );

        assertThat(result).isSameAs(expected);
        verify(memberService).assertActiveMember(MEMBER_ID);
        verify(petService).findOwnedActivePet(MEMBER_ID, PET_ID);
        verify(paymentMethodService).isActiveAndOwnedBy(
                MEMBER_ID,
                PAYMENT_METHOD_ID
        );
        verify(hospitalService).assertReservationRequestAvailable(HOSPITAL_ID);
        verify(reservationService).request(
                MEMBER_ID,
                request,
                "초코",
                "DOG"
        );
    }

    @Test
    @DisplayName("폐업 등으로 예약할 수 없는 병원에는 새 예약을 요청할 수 없다")
    void request_unavailableHospital_throwsHospitalReservationNotAvailable() {
        ReservationRequest request = new ReservationRequest(
                PET_ID,
                SLOT_ID,
                PAYMENT_METHOD_ID
        );
        given(reservationService.findSlot(SLOT_ID)).willReturn(slot());
        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.of(pet("초코", PetSpecies.DOG)));
        given(paymentMethodService.isActiveAndOwnedBy(
                MEMBER_ID,
                PAYMENT_METHOD_ID
        )).willReturn(true);
        org.mockito.Mockito.doThrow(new ServiceException(
                        HospitalErrorCode.HOSPITAL_RESERVATION_NOT_AVAILABLE
                ))
                .when(hospitalService)
                .assertReservationRequestAvailable(HOSPITAL_ID);

        assertThatThrownBy(() -> applicationService.request(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.HOSPITAL_RESERVATION_NOT_AVAILABLE);

        verify(petService).findOwnedActivePet(MEMBER_ID, PET_ID);
        verify(paymentMethodService).isActiveAndOwnedBy(
                MEMBER_ID,
                PAYMENT_METHOD_ID
        );
        verify(reservationService, never()).request(any(), any(), any(), any());
    }

    @Test
    @DisplayName("소유하지 않은 반려동물로 예약하면 프로필 필수 오류를 반환한다")
    void request_withoutOwnedPet_throwsProfileRequired() {
        ReservationRequest request = new ReservationRequest(
                PET_ID,
                SLOT_ID,
                PAYMENT_METHOD_ID
        );
        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> applicationService.request(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.PROFILE_REQUIRED);

        verify(paymentMethodService, never()).isActiveAndOwnedBy(any(), any());
        verify(reservationService, never()).request(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("소유하지 않거나 비활성인 결제수단으로 예약하면 결제수단 필수 오류를 반환한다")
    void request_withoutOwnedPaymentMethod_throwsPaymentMethodRequired() {
        ReservationRequest request = new ReservationRequest(
                PET_ID,
                SLOT_ID,
                PAYMENT_METHOD_ID
        );
        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.of(pet("초코", PetSpecies.DOG)));
        given(paymentMethodService.isActiveAndOwnedBy(
                MEMBER_ID,
                PAYMENT_METHOD_ID
        )).willReturn(false);

        assertThatThrownBy(() -> applicationService.request(MEMBER_ID, request))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.PAYMENT_METHOD_REQUIRED);

        verify(reservationService, never()).request(
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("대기열 수락도 REQUESTED 생성 직전에 병원 예약 가능 상태를 다시 확인한다")
    void requestFromWaitlist_unavailableHospital_throwsHospitalReservationNotAvailable() {
        given(petService.findOwnedActivePet(MEMBER_ID, PET_ID))
                .willReturn(Optional.of(pet("초코", PetSpecies.DOG)));
        given(paymentMethodService.isActiveAndOwnedBy(MEMBER_ID, PAYMENT_METHOD_ID)).willReturn(true);
        given(reservationService.findSlot(SLOT_ID)).willReturn(slot());
        org.mockito.Mockito.doThrow(new ServiceException(
                        HospitalErrorCode.HOSPITAL_RESERVATION_NOT_AVAILABLE
                ))
                .when(hospitalService)
                .assertReservationRequestAvailable(HOSPITAL_ID);

        assertThatThrownBy(() -> applicationService.requestFromWaitlist(
                MEMBER_ID, PET_ID, PAYMENT_METHOD_ID, SLOT_ID))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.HOSPITAL_RESERVATION_NOT_AVAILABLE);

        verify(reservationService, never()).requestFromWaitlist(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("보호자는 결제가 시작되기 전 REQUESTED 예약의 활성 본인 결제수단을 재지정할 수 있다")
    void changePaymentMethod_beforePayment_updatesReservation() {
        Reservation reservation = reservation();
        ReservationPaymentMethodUpdateRequest request = new ReservationPaymentMethodUpdateRequest(8L);
        given(reservationService.findMyReservationForUpdate(MEMBER_ID, 10L)).willReturn(reservation);
        given(paymentQueryService.findStatusByReservationIdForUpdate(10L)).willReturn(Optional.empty());

        applicationService.changePaymentMethod(MEMBER_ID, 10L, request);

        assertThat(reservation.getPaymentMethodId()).isEqualTo(8L);
        verify(paymentMethodService).assertActiveAndOwnedBy(MEMBER_ID, 8L);
    }

    @Test
    @DisplayName("Payment 선기록이 있으면 예약 결제수단 재지정을 차단한다")
    void changePaymentMethod_afterPaymentStarted_throws() {
        Reservation reservation = reservation();
        given(reservationService.findMyReservationForUpdate(MEMBER_ID, 10L)).willReturn(reservation);
        given(paymentQueryService.findStatusByReservationIdForUpdate(10L))
                .willReturn(Optional.of(PaymentStatus.PENDING));

        assertThatThrownBy(() -> applicationService.changePaymentMethod(
                MEMBER_ID,
                10L,
                new ReservationPaymentMethodUpdateRequest(8L)
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(ReservationErrorCode.PAYMENT_ALREADY_STARTED);
        assertThat(reservation.getPaymentMethodId()).isEqualTo(PAYMENT_METHOD_ID);
    }

    @Test
    @DisplayName("예약 목록은 예약의 슬롯 시간과 병원명을 조합한다")
    void getMyReservations_combinesSlotAndHospital() {
        Reservation reservation = reservation();
        ReservationSlot slot = slot();
        HospitalSummaryResponse hospital = new HospitalSummaryResponse(
                HOSPITAL_ID,
                "닥터펫 동물병원"
        );
        ReservationListCondition condition = new ReservationListCondition(
                null,
                null,
                null,
                0,
                20,
                "reservedAt,desc"
        );
        given(reservationService.findMyReservations(MEMBER_ID, condition))
                .willReturn(new PageImpl<>(List.of(reservation)));
        given(reservationService.findSlots(List.of(SLOT_ID)))
                .willReturn(List.of(slot));
        given(hospitalService.getHospitalSummaries(List.of(HOSPITAL_ID)))
                .willReturn(List.of(hospital));

        ReservationPageResponse result = applicationService.getMyReservations(
                MEMBER_ID,
                condition
        );

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).hospitalName())
                .isEqualTo("닥터펫 동물병원");
        assertThat(result.content().get(0).reservedAt())
                .isEqualTo(slot.getStartAt());
        assertThat(result.totalElements()).isEqualTo(1);
        verify(memberService).assertActiveMember(MEMBER_ID);
    }

    @Test
    @DisplayName("예약이 참조한 슬롯이 없으면 목록 전체를 실패시킨다")
    void getMyReservations_missingSlot_throwsSlotNotFound() {
        Reservation reservation = reservation();
        ReservationListCondition condition = defaultCondition();
        given(reservationService.findMyReservations(MEMBER_ID, condition))
                .willReturn(new PageImpl<>(List.of(reservation)));
        given(reservationService.findSlots(List.of(SLOT_ID)))
                .willReturn(List.of());
        given(hospitalService.getHospitalSummaries(List.of(HOSPITAL_ID)))
                .willReturn(List.of(new HospitalSummaryResponse(
                        HOSPITAL_ID,
                        "닥터펫 동물병원"
                )));

        assertThatThrownBy(() -> applicationService.getMyReservations(
                MEMBER_ID,
                condition
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(SlotErrorCode.SLOT_NOT_FOUND);
    }

    @Test
    @DisplayName("예약이 참조한 병원이 없으면 목록 전체를 실패시킨다")
    void getMyReservations_missingHospital_throwsHospitalNotFound() {
        Reservation reservation = reservation();
        ReservationListCondition condition = defaultCondition();
        given(reservationService.findMyReservations(MEMBER_ID, condition))
                .willReturn(new PageImpl<>(List.of(reservation)));
        given(reservationService.findSlots(List.of(SLOT_ID)))
                .willReturn(List.of(slot()));
        given(hospitalService.getHospitalSummaries(List.of(HOSPITAL_ID)))
                .willReturn(List.of());

        assertThatThrownBy(() -> applicationService.getMyReservations(
                MEMBER_ID,
                condition
        ))
                .isInstanceOf(ServiceException.class)
                .extracting("errorCode")
                .isEqualTo(HospitalErrorCode.HOSPITAL_NOT_FOUND);
    }

    private PetResponse pet(String name, PetSpecies species) {
        return new PetResponse(
                PET_ID,
                name,
                species,
                5,
                BigDecimal.valueOf(4.8),
                true,
                null
        );
    }

    private Reservation reservation() {
        Reservation reservation = Reservation.request(
                MEMBER_ID,
                PET_ID,
                HOSPITAL_ID,
                SLOT_ID,
                PAYMENT_METHOD_ID,
                "초코",
                "DOG",
                LocalDateTime.now()
        );
        ReflectionTestUtils.setField(reservation, "id", 10L);
        return reservation;
    }

    private ReservationSlot slot() {
        LocalDateTime startAt = LocalDateTime.now().plusDays(1);
        ReservationSlot slot = ReservationSlot.create(
                HOSPITAL_ID,
                startAt,
                startAt.plusMinutes(30)
        );
        ReflectionTestUtils.setField(slot, "id", SLOT_ID);
        return slot;
    }

    private ReservationListCondition defaultCondition() {
        return new ReservationListCondition(
                null,
                null,
                null,
                0,
                20,
                "reservedAt,desc"
        );
    }
}
