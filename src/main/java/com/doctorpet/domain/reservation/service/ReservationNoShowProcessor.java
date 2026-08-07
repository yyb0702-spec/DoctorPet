package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.reservation.config.ReservationNoShowProperties;
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
    private final ReservationNoShowProperties properties;

    @Transactional
    public Result process(Long reservationId, LocalDateTime now) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) return Result.SKIPPED;

        LocalDateTime pendingCutoff = now.minusMinutes(properties.getGraceMinutes());
        LocalDateTime finalCutoff = pendingCutoff.minusMinutes(
                properties.getPendingGraceMinutes()
        );
        int pendingUpdated = reservationRepository.markAutoNoShowPendingIfConfirmed(
                reservationId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.NO_SHOW_PENDING,
                now,
                pendingCutoff
        );
        if (pendingUpdated == 1) {
            reservationEventRepository.appendIfAbsent(
                    reservationId,
                    ReservationEventType.AUTO_NO_SHOW_PENDING.name(),
                    "예약 시각 이후 체크인 미확인으로 최종 노쇼 판정 대기",
                    null,
                    now
            );
        }

        int finalUpdated = reservationRepository.markAutoNoShowIfPending(
                reservationId,
                ReservationStatus.NO_SHOW_PENDING,
                ReservationStatus.NO_SHOW,
                now,
                finalCutoff
        );
        if (finalUpdated == 1) {
            reservationEventRepository.appendIfAbsent(
                    reservationId,
                    ReservationEventType.AUTO_NO_SHOW.name(),
                    "추가 유예시간 경과 후 체크인 미확인으로 자동 판정",
                    null,
                    now
            );
            notificationPublisher.publishNoShow(reservation.getMemberId(), reservationId);
        }

        return pendingUpdated == 1 || finalUpdated == 1
                ? Result.PROCESSED
                : Result.SKIPPED;
    }
}
