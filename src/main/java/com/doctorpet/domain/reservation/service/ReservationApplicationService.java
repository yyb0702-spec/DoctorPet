package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.hospital.dto.response.HospitalDetailResponse;
import com.doctorpet.domain.hospital.dto.response.HospitalSummaryResponse;
import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.payment.service.PaymentMethodService;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.domain.pet.dto.response.PetResponse;
import com.doctorpet.domain.pet.service.PetService;
import com.doctorpet.domain.reservation.dto.request.ReservationListCondition;
import com.doctorpet.domain.reservation.dto.request.ReservationRequest;
import com.doctorpet.domain.reservation.dto.request.ReservationPaymentMethodUpdateRequest;
import com.doctorpet.domain.reservation.dto.response.ReservationDetailResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationListItemResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationPageResponse;
import com.doctorpet.domain.reservation.dto.response.ReservationResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
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
    private final PaymentQueryService paymentQueryService;
    private final HospitalService hospitalService;
    private final ReservationNotificationPublisher notificationPublisher;

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

        ReservationSlot slot = reservationService.findSlot(request.slotId());
        hospitalService.assertReservationRequestAvailable(slot.getHospitalId());

        ReservationResponse reservation = reservationService.request(
                memberId,
                request,
                pet.name(),
                pet.species().name()
        );
        // 병원이 확인해야 할 REQUESTED 예약이 생겼으므로 같은 트랜잭션 안에서 병원 수신 알림을 남긴다(#166).
        // 예약 생성이 롤백되면 알림도 남지 않는다(기존 승인·거절 발행과 동일 계약). 실시간 전송(SSE)은
        // 커밋 이후 AFTER_COMMIT 리스너가 처리한다.
        notificationPublisher.publishReservationRequested(
                reservation.hospitalId(),
                reservation.reservationId()
        );
        return reservation;
    }

    @Transactional
    public ReservationResponse requestFromWaitlist(
            Long memberId,
            Long petId,
            Long paymentMethodId,
            Long slotId
    ) {
        memberService.assertActiveMember(memberId);
        PetResponse pet = petService.findOwnedActivePet(memberId, petId)
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.PROFILE_REQUIRED));
        if (!paymentMethodService.isActiveAndOwnedBy(memberId, paymentMethodId)) {
            throw new ServiceException(ReservationErrorCode.PAYMENT_METHOD_REQUIRED);
        }
        ReservationSlot slot = reservationService.findSlot(slotId);
        // 대기열 제안 이후 병원이 휴무·폐업 상태로 바뀔 수 있으므로, 일반 예약과 동일하게
        // REQUESTED 생성 직전에 병원의 현재 예약 가능 상태를 다시 확인한다.
        hospitalService.assertReservationRequestAvailable(slot.getHospitalId());
        ReservationResponse reservation = reservationService.requestFromWaitlist(
                memberId,
                petId,
                paymentMethodId,
                slotId,
                pet.name(),
                pet.species().name()
        );
        // 대기열 승급 수락도 병원 입장에서는 확인해야 할 새 REQUESTED 예약이다 — 일반 요청과 동일하게 발행해
        // 대기열 출신 예약만 병원 알림에서 빠지는 구멍을 막는다(#166).
        notificationPublisher.publishReservationRequested(
                reservation.hospitalId(),
                reservation.reservationId()
        );
        return reservation;
    }

    @Transactional
    public void cancel(Long memberId, Long reservationId) {
        memberService.assertActiveMember(memberId);
        reservationService.cancel(memberId, reservationId);
    }

    @Transactional
    public void changePaymentMethod(
            Long memberId,
            Long reservationId,
            ReservationPaymentMethodUpdateRequest request
    ) {
        memberService.assertActiveMember(memberId);
        Reservation reservation = reservationService.findMyReservationForUpdate(memberId, reservationId);
        paymentMethodService.assertActiveAndOwnedBy(memberId, request.paymentMethodId());
        // 일반 exists 조회는 REPEATABLE READ의 일관 읽기 스냅샷에 묶여, 바로 앞 청구 트랜잭션이
        // 예약 행 잠금을 해제하며 커밋한 Payment를 보지 못할 수 있다. FOR UPDATE 현재 읽기로
        // 선기록을 확인해 같은 직렬화 경합에서 PAYMENT_ALREADY_STARTED을 일관되게 반환한다.
        if (paymentQueryService.findStatusByReservationIdForUpdate(reservationId).isPresent()) {
            throw new ServiceException(ReservationErrorCode.PAYMENT_ALREADY_STARTED);
        }
        reservation.changePaymentMethod(request.paymentMethodId());
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
