package com.doctorpet.domain.review.service;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.domain.review.dto.request.ReviewRequest;
import com.doctorpet.domain.review.dto.response.HospitalReviewItemResponse;
import com.doctorpet.domain.review.dto.response.ReviewPageResponse;
import com.doctorpet.domain.review.dto.response.ReviewResponse;
import com.doctorpet.domain.review.entity.Review;
import com.doctorpet.domain.review.exception.ReviewErrorCode;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewApplicationService {

    private static final String MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE = "23000";
    private static final int MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062;
    private static final String RESERVATION_ID_UNIQUE_CONSTRAINT =
            "uk_reviews_reservation_id";

    private static final Set<PaymentStatus> ELIGIBLE_PAYMENT_STATUSES = Set.of(
            PaymentStatus.PAID,
            PaymentStatus.OFFLINE_PAID
    );

    private final ReviewRepository reviewRepository;
    private final HospitalService hospitalService;
    private final ReservationService reservationService;
    private final PaymentQueryService paymentQueryService;
    private final Clock applicationClock;

    @Transactional(readOnly = true)
    public ReviewPageResponse getHospitalReviews(Long hospitalId, int page, int size) {
        if (!hospitalService.exists(hospitalId)) {
            throw new ServiceException(HospitalErrorCode.HOSPITAL_NOT_FOUND);
        }
        PageRequest pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );
        return ReviewPageResponse.from(
                reviewRepository.findByHospitalId(hospitalId, pageable)
                        .map(HospitalReviewItemResponse::from)
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewResponse create(
            Long memberId,
            Long reservationId,
            ReviewRequest request
    ) {
        Long hospitalId = validateAndGetHospitalId(memberId, reservationId);

        LocalDateTime reviewedAt = LocalDateTime.now(applicationClock);
        int claimed = reservationService.claimReviewOpportunity(
                reservationId,
                memberId,
                reviewedAt
        );
        if (claimed != 1) {
            throw new ServiceException(ReviewErrorCode.ALREADY_REVIEWED);
        }

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
            if (isReservationIdDuplicate(e)) {
                throw new ServiceException(ReviewErrorCode.ALREADY_REVIEWED);
            }
            throw e;
        }
    }

    @Transactional
    public ReviewResponse update(
            Long memberId,
            Long reviewId,
            ReviewRequest request
    ) {
        Review review = reviewRepository.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ServiceException(
                        ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!review.getMemberId().equals(memberId)) {
            throw new ServiceException(ReviewErrorCode.NOT_REVIEW_AUTHOR);
        }
        review.update(request.rating(), request.content());
        reviewRepository.flush();
        return ReviewResponse.from(review);
    }

    @Transactional
    public void delete(Long memberId, Long reviewId) {
        Review review = reviewRepository.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ServiceException(
                        ReviewErrorCode.REVIEW_NOT_FOUND));
        if (!review.getMemberId().equals(memberId)) {
            throw new ServiceException(ReviewErrorCode.NOT_REVIEW_AUTHOR);
        }
        reviewRepository.delete(review);
    }

    // 동일 예약의 리뷰 중복(UNIQUE 위반)만 ALREADY_REVIEWED로 변환하고,
    // CHECK·NOT NULL 등 다른 무결성 위반은 원래 예외로 전파하기 위해 구분한다.
    private boolean isReservationIdDuplicate(DataIntegrityViolationException exception) {
        if (!(exception.getMostSpecificCause() instanceof SQLException sqlException)) {
            return false;
        }
        if (!MYSQL_INTEGRITY_CONSTRAINT_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())
                || sqlException.getErrorCode() != MYSQL_DUPLICATE_ENTRY_ERROR_CODE) {
            return false;
        }
        String message = sqlException.getMessage();
        return message != null
                && message.toLowerCase(Locale.ROOT)
                .contains(RESERVATION_ID_UNIQUE_CONSTRAINT);
    }

    private Long validateAndGetHospitalId(Long memberId, Long reservationId) {
        if (!reservationService.exists(reservationId)) {
            throw new ServiceException(ReviewErrorCode.RESERVATION_NOT_FOUND);
        }
        Long hospitalId = reservationService
                .findHospitalIdForOwner(reservationId, memberId)
                .orElseThrow(() -> new ServiceException(
                        ReviewErrorCode.NOT_RESERVATION_OWNER));
        validateReviewOpportunity(reservationId);
        return hospitalId;
    }

    private void validateReviewOpportunity(Long reservationId) {
        if (reservationService.isReviewed(reservationId)) {
            throw new ServiceException(ReviewErrorCode.ALREADY_REVIEWED);
        }
        PaymentStatus paymentStatus = paymentQueryService
                .findStatusByReservationIdForUpdate(reservationId)
                .orElse(null);
        if (!ELIGIBLE_PAYMENT_STATUSES.contains(paymentStatus)) {
            throw new ServiceException(ReviewErrorCode.PAYMENT_NOT_COMPLETED);
        }
    }
}
