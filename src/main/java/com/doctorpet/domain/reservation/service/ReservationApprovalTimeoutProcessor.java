package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.exception.SlotErrorCode;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
import com.doctorpet.domain.reservation.repository.ReservationEventRepository;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.repository.ReservationSlotRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReservationApprovalTimeoutProcessor {

    public enum Result {
        PROCESSED,
        SKIPPED,
        FAILED
    }

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository reservationSlotRepository;
    private final ReservationEventRepository reservationEventRepository;
    private final ReservationNotificationPublisher notificationPublisher;

    @Transactional
    public Result process(
            Long reservationId,
            LocalDateTime now
    ) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ServiceException(
                        ReservationErrorCode.RESERVATION_NOT_FOUND
                ));

        int updated = reservationRepository.rejectByTimeoutIfRequested(
                reservationId,
                ReservationStatus.REQUESTED,
                ReservationStatus.REJECTED,
                now
        );

        if (updated == 0) {
            return Result.SKIPPED;
        }

        ReservationSlot slot = reservationSlotRepository
                .findById(reservation.getSlotId())
                .orElseThrow(() -> new ServiceException(
                        SlotErrorCode.SLOT_NOT_FOUND
                ));

        slot.open();

        reservationEventRepository.appendIfAbsent(
                reservationId,
                ReservationEventType.TIMEOUT_REJECTED.name(),
                "승인 마감 시간 초과",
                null,
                now
        );

        notificationPublisher.publishRejected(
                reservation.getMemberId(),
                reservationId
        );

        return Result.PROCESSED;
    }

    /** 실패 예약의 다음 재시도 시각 저장은 별도 짧은 트랜잭션으로 보장한다. */
    @Transactional
    public void deferRetry(Long reservationId, LocalDateTime nextRetryAt) {
        reservationRepository.deferApprovalTimeoutRetry(
                reservationId,
                ReservationStatus.REQUESTED,
                nextRetryAt
        );
    }
}
