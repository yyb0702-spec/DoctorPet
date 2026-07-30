package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSummaryResponse;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.service.PetService;
import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationDetailResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationListItemResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationPageResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationApplicationService {

    private final ReservationService reservationService;
    private final MemberService memberService;
    private final PetService petService;
    private final PaymentMethodService paymentMethodService;
    private final HospitalService hospitalService;

    @Transactional
    public ReservationResponse request(
            Long memberId,
            ReservationRequest request
    ) {
        memberService.assertActiveMember(memberId);

        PetResponse pet = petService.findOwnedActivePet(memberId, request.petId())
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.PROFILE_REQUIRED
                ));

        if (!paymentMethodService.isActiveAndOwnedBy(
                memberId,
                request.paymentMethodId()
        )) {
            throw new ServiceException(
                    ReservationErrorCode.PAYMENT_METHOD_REQUIRED
            );
        }

        return reservationService.request(
                memberId,
                request,
                pet.name(),
                pet.species().name()
        );
    }

    @Transactional
    public void cancel(Long memberId, Long reservationId) {
        memberService.assertActiveMember(memberId);
        reservationService.cancel(memberId, reservationId);
    }

    public ReservationDetailResponse getMyReservation(
            Long memberId,
            Long reservationId
    ) {
        memberService.assertActiveMember(memberId);

        Reservation reservation = reservationService.findMyReservation(
                memberId,
                reservationId
        );
        ReservationSlot slot = reservationService.findSlot(reservation.getSlotId());
        HospitalDetailResponse hospital = hospitalService
                .getHospitalDetail(reservation.getHospitalId());

        return ReservationDetailResponse.from(
                reservation,
                slot,
                hospital,
                null
        );
    }

    public ReservationPageResponse getMyReservations(
            Long memberId,
            ReservationListCondition condition
    ) {
        memberService.assertActiveMember(memberId);

        Page<Reservation> reservations = reservationService.findMyReservations(
                memberId,
                condition
        );

        Map<Long, ReservationSlot> slotsById = reservationService.findSlots(
                        reservations.stream()
                                .map(Reservation::getSlotId)
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(
                        ReservationSlot::getId,
                        Function.identity()
                ));

        Map<Long, HospitalSummaryResponse> hospitalsById = hospitalService
                .getHospitalSummaries(
                        reservations.stream()
                                .map(Reservation::getHospitalId)
                                .distinct()
                                .toList()
                ).stream()
                .collect(Collectors.toMap(
                        HospitalSummaryResponse::hospitalId,
                        Function.identity()
                ));

        Page<ReservationListItemResponse> responsePage = reservations.map(
                reservation -> {
                    ReservationSlot slot = requireSlot(
                            slotsById,
                            reservation.getSlotId()
                    );
                    HospitalSummaryResponse hospital = requireHospital(
                            hospitalsById,
                            reservation.getHospitalId()
                    );

                    return ReservationListItemResponse.from(
                            reservation,
                            hospital.name(),
                            slot.getStartAt(),
                            null
                    );
                }
        );

        return ReservationPageResponse.from(responsePage);
    }

    private ReservationSlot requireSlot(
            Map<Long, ReservationSlot> slotsById,
            Long slotId
    ) {
        ReservationSlot slot = slotsById.get(slotId);
        if (slot == null) {
            throw new ServiceException(SlotErrorCode.SLOT_NOT_FOUND);
        }
        return slot;
    }

    private HospitalSummaryResponse requireHospital(
            Map<Long, HospitalSummaryResponse> hospitalsById,
            Long hospitalId
    ) {
        HospitalSummaryResponse hospital = hospitalsById.get(hospitalId);
        if (hospital == null) {
            throw new ServiceException(HospitalErrorCode.HOSPITAL_NOT_FOUND);
        }
        return hospital;
    }
}
