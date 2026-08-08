package com.doctorpet.domain.review.service;

import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewRefundService {

    private final ReviewRepository reviewRepository;
    private final ReservationService reservationService;

    @Transactional
    public void deleteAndReset(Long reservationId) {
        reviewRepository.deleteByReservationId(reservationId);
        reservationService.resetReviewed(reservationId);
    }
}
