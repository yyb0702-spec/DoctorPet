package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.status.ReservationEventType;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import com.doctorpet.domain.reservation.repository.ReservationEventRepository;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.domain.reservation.notification.ReservationNotificationPublisher;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationNoShowProcessor {

    public enum Result { PROCESSED, SKIPPED }

    private final ReservationRepository reservationRepository;
    private final ReservationEventRepository reservationEventRepository;
    private final ReservationNotificationPublisher notificationPublisher;

    @Transactional
    public Result process(Long reservationId, LocalDateTime now) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) return Result.SKIPPED;

        int updated = reservationRepository.markAutoNoShowIfConfirmed(
                reservationId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW,
                now
        );
        if (updated == 0) return Result.SKIPPED;

        reservationEventRepository.appendIfAbsent(
                reservationId,
                ReservationEventType.AUTO_NO_SHOW.name(),
                "예약 시각 +10분 경과 후 미체크인",
                null,
                now
        );
        notificationPublisher.publishNoShow(reservation.getMemberId(), reservationId);
        return Result.PROCESSED;
    }
}
