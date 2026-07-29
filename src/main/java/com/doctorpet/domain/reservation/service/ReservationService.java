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
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.lock.ReservationLockStrategy;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationLockStrategy reservationLockStrategy;
    private final MemberService memberService;
    private final PetService petService;
    private final PaymentMethodService paymentMethodService;
    private final HospitalService hospitalService;

    @Transactional
    public ReservationResponse request(Long memberId, ReservationRequest request) {
        LocalDateTime now = LocalDateTime.now();

        memberService.assertActiveMember(memberId);

        ReservationSlot slot = reservationSlotRepository.findById(request.slotId())
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (slot.getStartAt().isBefore(now.plusHours(4))) {
            throw new ServiceException(ReservationErrorCode.LEAD_TIME_VIOLATION);
        }

        PetResponse pet = petService
                .findOwnedActivePet(memberId, request.petId())
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

        slot = reservationLockStrategy.reserve(request.slotId());

        Reservation reservation = Reservation.request(
                memberId,
                request.petId(),
                slot.getHospitalId(),
                slot.getId(),
                request.paymentMethodId(),
                pet.name(),
                pet.species().name(),
                now
        );

        return ReservationResponse.from(reservationRepository.save(reservation));
    }

    @Transactional
    public void cancel(Long memberId, Long reservationId) {
        LocalDateTime now = LocalDateTime.now();

        Reservation reservation = reservationRepository
                .findByIdAndMemberId(reservationId, memberId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));

        ReservationSlot slot = reservationSlotRepository.findById(reservation.getSlotId())
                .orElseThrow(() -> new ServiceException(SlotErrorCode.SLOT_NOT_FOUND));

        if (now.isAfter(slot.getStartAt().minusHours(2))) {
            throw new ServiceException(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        }

        int updated = reservationRepository.cancelIfAllowed(
                reservationId,
                memberId,
                ReservationStatus.REQUESTED,
                ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELED,
                now
        );
        if (updated == 0) {
            throw new ServiceException(ReservationErrorCode.INVALID_STATUS);
        }

        slot.open();
    }

    @Transactional(readOnly = true)
    public ReservationDetailResponse getMyReservation(
            Long memberId,
            Long reservationId
    ) {
        memberService.assertActiveMember(memberId);

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));
        if (!reservation.isOwnedBy(memberId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }

        ReservationSlot slot = reservationSlotRepository
                .findById(reservation.getSlotId())
                .orElseThrow(() -> new ServiceException(
                        SlotErrorCode.SLOT_NOT_FOUND
                ));

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
        validatePage(condition);
        validateDateRange(condition.from(), condition.to());

        ReservationStatus status = parseStatus(condition.status());
        Sort.Direction direction = parseSort(condition.sort());
        LocalDateTime fromAt = startOfDay(condition.from());
        LocalDateTime toExclusive = startOfNextDay(condition.to());
        PageRequest pageRequest = PageRequest.of(
                condition.page(),
                condition.size()
        );

        Page<Reservation> reservations = reservationRepository
                .findMyReservations(
                        memberId,
                        status,
                        fromAt,
                        toExclusive,
                        direction,
                        pageRequest
                );

        if (reservations.isEmpty()) {
            return ReservationPageResponse.from(
                    Page.empty(pageRequest)
            );
        }

        Map<Long, ReservationSlot> slotsById =
                reservationSlotRepository.findAllById(
                                reservations.stream()
                                        .map(Reservation::getSlotId)
                                        .distinct()
                                        .toList()
                        ).stream()
                        .collect(Collectors.toMap(
                                ReservationSlot::getId,
                                Function.identity()
                        ));

        Map<Long, HospitalSummaryResponse> hospitalsById =
                hospitalService.getHospitalSummaries(
                                reservations.stream()
                                        .map(Reservation::getHospitalId)
                                        .distinct()
                                        .toList()
                        ).stream()
                        .collect(Collectors.toMap(
                                HospitalSummaryResponse::hospitalId,
                                Function.identity()
                        ));

        Page<ReservationListItemResponse> responsePage =
                reservations.map(reservation -> {
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
                });

        return ReservationPageResponse.from(responsePage);
    }

    private void validatePage(ReservationListCondition condition) {
        if (condition.page() < 0 || condition.size() < 1) {
            throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ServiceException(
                    ReservationErrorCode.INVALID_DATE_RANGE
            );
        }
    }

    private ReservationStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }

        try {
            return ReservationStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(
                    ReservationErrorCode.INVALID_FILTER_STATUS
            );
        }
    }

    private Sort.Direction parseSort(String sort) {
        String resolvedSort = sort == null
                ? "reservedAt,desc"
                : sort.trim();
        String[] parts = resolvedSort.split(",", -1);

        if (parts.length != 2
                || !"reservedAt".equals(parts[0].trim())) {
            throw new ServiceException(ReservationErrorCode.INVALID_SORT);
        }

        try {
            return Sort.Direction.fromString(parts[1].trim());
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(ReservationErrorCode.INVALID_SORT);
        }
    }

    private LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDateTime startOfNextDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
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
            throw new ServiceException(
                    HospitalErrorCode.HOSPITAL_NOT_FOUND
            );
        }
        return hospital;
    }
}
