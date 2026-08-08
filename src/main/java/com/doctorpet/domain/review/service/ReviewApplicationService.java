package com.doctorpet.domain.review.service;

import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.domain.review.dto.request.ReviewCreateRequest;
import com.doctorpet.domain.review.dto.response.ReviewResponse;
import com.doctorpet.domain.review.entity.Review;
import com.doctorpet.domain.review.exception.ReviewErrorCode;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewApplicationService {

    private static final Set<PaymentStatus> ELIGIBLE_PAYMENT_STATUSES = Set.of(
            PaymentStatus.PAID,
            PaymentStatus.OFFLINE_PAID
    );

    private final ReviewRepository reviewRepository;
    private final ReservationService reservationService;
    private final PaymentQueryService paymentQueryService;
    private final Clock applicationClock;

    @Transactional
    public ReviewResponse create(
            Long memberId,
            Long reservationId,
            ReviewCreateRequest request
    ) {
        Long hospitalId = validateAndGetHospitalId(memberId, reservationId);

        LocalDateTime reviewedAt = LocalDateTime.now(applicationClock);
        reservationService.markReviewed(reservationId, reviewedAt);

        Review review = Review.create(
                reservationId,
                hospitalId,
                memberId,
                request.rating(),
                request.content()
        );
        try {
            return ReviewResponse.from(reviewRepository.saveAndFlush(review));
        } catch (DataIntegrityViolationException e) {
            throw new ServiceException(ReviewErrorCode.ALREADY_REVIEWED);
        }
    }

    private Long validateAndGetHospitalId(Long memberId, Long reservationId) {
        if (!reservationService.exists(reservationId)) {
            throw new ServiceException(ReviewErrorCode.RESERVATION_NOT_FOUND);
        }
        Long hospitalId = reservationService
                .findHospitalIdForOwner(reservationId, memberId)
                .orElseThrow(() -> new ServiceException(
                        ReviewErrorCode.NOT_RESERVATION_OWNER));
        if (reservationService.isReviewed(reservationId)) {
            throw new ServiceException(ReviewErrorCode.ALREADY_REVIEWED);
        }
        PaymentStatus paymentStatus = paymentQueryService
                .findStatusByReservationId(reservationId)
                .orElse(null);
        if (!ELIGIBLE_PAYMENT_STATUSES.contains(paymentStatus)) {
            throw new ServiceException(ReviewErrorCode.PAYMENT_NOT_COMPLETED);
        }
        return hospitalId;
    }
}
