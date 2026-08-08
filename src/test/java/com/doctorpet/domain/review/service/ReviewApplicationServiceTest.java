package com.doctorpet.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.hospital.service.HospitalService;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.service.PaymentQueryService;
import com.doctorpet.domain.review.dto.request.ReviewRequest;
import com.doctorpet.domain.review.dto.response.ReviewResponse;
import com.doctorpet.domain.review.dto.response.ReviewPageResponse;
import com.doctorpet.domain.review.entity.Review;
import com.doctorpet.domain.review.exception.ReviewErrorCode;
import com.doctorpet.domain.review.repository.ReviewRepository;
import com.doctorpet.domain.reservation.service.ReservationService;
import com.doctorpet.global.exception.ServiceException;
import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ReviewApplicationServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long RESERVATION_ID = 10L;
    private static final Long HOSPITAL_ID = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 8, 12, 0);

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private HospitalService hospitalService;

    @Mock
    private ReservationService reservationService;

    @Mock
    private PaymentQueryService paymentQueryService;

    private ReviewApplicationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-08T03:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );
        service = new ReviewApplicationService(
                reviewRepository,
                hospitalService,
                reservationService,
                paymentQueryService,
                clock
        );
    }

    @Test
    @DisplayName("PAID인 본인 예약의 작성권을 선점하고 리뷰를 저장한다")
    void create_paidOwnReservation_succeeds() {
        given(reservationService.exists(RESERVATION_ID)).willReturn(true);
        given(reservationService.findHospitalIdForOwner(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(HOSPITAL_ID));
        given(paymentQueryService.findStatusByReservationIdForUpdate(RESERVATION_ID))
                .willReturn(Optional.of(PaymentStatus.PAID));
        given(reservationService.claimReviewOpportunity(
                RESERVATION_ID,
                MEMBER_ID,
                NOW
        )).willReturn(1);
        given(reviewRepository.saveAndFlush(any(Review.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ReviewResponse response = service.create(MEMBER_ID, RESERVATION_ID, request());

        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(response.rating()).isEqualByComparingTo("4.5");
        verify(reservationService).claimReviewOpportunity(
                RESERVATION_ID,
                MEMBER_ID,
                NOW
        );
        verify(reviewRepository).saveAndFlush(any(Review.class));
    }

    @Test
    @DisplayName("다른 회원의 예약에는 리뷰를 작성할 수 없다")
    void create_otherMemberReservation_throwsForbidden() {
        given(reservationService.exists(RESERVATION_ID)).willReturn(true);
        given(reservationService.findHospitalIdForOwner(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(MEMBER_ID, RESERVATION_ID, request()))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ReviewErrorCode.NOT_RESERVATION_OWNER));
    }

    @Test
    @DisplayName("결제가 완료되지 않은 예약에는 리뷰를 작성할 수 없다")
    void create_pendingPayment_throwsConflict() {
        given(reservationService.exists(RESERVATION_ID)).willReturn(true);
        given(reservationService.findHospitalIdForOwner(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(HOSPITAL_ID));
        given(paymentQueryService.findStatusByReservationIdForUpdate(RESERVATION_ID))
                .willReturn(Optional.of(PaymentStatus.PENDING));

        assertThatThrownBy(() -> service.create(MEMBER_ID, RESERVATION_ID, request()))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ReviewErrorCode.PAYMENT_NOT_COMPLETED));
    }

    @Test
    @DisplayName("사용자가 이미 작성권을 사용했다면 리뷰 행이 없어도 재작성을 막는다")
    void create_reviewedAtExists_throwsConflict() {
        given(reservationService.exists(RESERVATION_ID)).willReturn(true);
        given(reservationService.findHospitalIdForOwner(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(HOSPITAL_ID));
        given(reservationService.isReviewed(RESERVATION_ID)).willReturn(true);

        assertThatThrownBy(() -> service.create(MEMBER_ID, RESERVATION_ID, request()))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ReviewErrorCode.ALREADY_REVIEWED));
    }

    @Test
    @DisplayName("작성권 선점에 실패하면 이미 리뷰를 작성한 것으로 처리한다")
    void create_claimFails_throwsAlreadyReviewed() {
        given(reservationService.exists(RESERVATION_ID)).willReturn(true);
        given(reservationService.findHospitalIdForOwner(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(HOSPITAL_ID));
        given(reservationService.isReviewed(RESERVATION_ID)).willReturn(false);
        given(paymentQueryService.findStatusByReservationIdForUpdate(RESERVATION_ID))
                .willReturn(Optional.of(PaymentStatus.PAID));
        given(reservationService.claimReviewOpportunity(
                RESERVATION_ID,
                MEMBER_ID,
                NOW
        )).willReturn(0);

        assertThatThrownBy(() -> service.create(MEMBER_ID, RESERVATION_ID, request()))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ReviewErrorCode.ALREADY_REVIEWED));
        verify(reviewRepository, never()).saveAndFlush(any(Review.class));
    }

    @Test
    @DisplayName("예약 ID UNIQUE 제약 위반은 이미 리뷰를 작성한 것으로 변환한다")
    void create_reservationIdUniqueViolation_mapsToAlreadyReviewed() {
        givenReviewableReservation(PaymentStatus.PAID);
        given(reviewRepository.saveAndFlush(any(Review.class)))
                .willThrow(new DataIntegrityViolationException(
                        "could not execute statement",
                        new SQLIntegrityConstraintViolationException(
                                "Duplicate entry '10' for key 'reviews.uk_reviews_reservation_id'",
                                "23000",
                                1062
                        )
                ));

        assertThatThrownBy(() -> service.create(MEMBER_ID, RESERVATION_ID, request()))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ReviewErrorCode.ALREADY_REVIEWED));
    }

    @Test
    @DisplayName("예약 ID UNIQUE 이외의 무결성 위반은 원래 예외를 전파한다")
    void create_otherIntegrityViolation_propagatesAsIs() {
        givenReviewableReservation(PaymentStatus.PAID);
        DataIntegrityViolationException otherViolation = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLIntegrityConstraintViolationException(
                        "Check constraint 'ck_reviews_rating' is violated",
                        "HY000",
                        3819
                )
        );
        given(reviewRepository.saveAndFlush(any(Review.class)))
                .willThrow(otherViolation);

        assertThatThrownBy(() -> service.create(MEMBER_ID, RESERVATION_ID, request()))
                .isSameAs(otherViolation);
    }

    @Test
    @DisplayName("병원 리뷰 목록은 최신 작성순과 ID 역순으로 조회한다")
    void getHospitalReviews_ordersByCreatedAtAndIdDescending() {
        Review review = Review.create(
                RESERVATION_ID,
                HOSPITAL_ID,
                MEMBER_ID,
                new BigDecimal("4.5"),
                "친절했어요."
        );
        given(hospitalService.exists(HOSPITAL_ID)).willReturn(true);
        given(reviewRepository.findByHospitalId(eq(HOSPITAL_ID), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(review)));

        ReviewPageResponse response = service.getHospitalReviews(HOSPITAL_ID, 1, 20);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.content()).hasSize(1);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewRepository).findByHospitalId(eq(HOSPITAL_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending())
                .isTrue();
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").isDescending())
                .isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 병원의 리뷰 목록은 조회할 수 없다")
    void getHospitalReviews_hospitalNotFound_throwsNotFound() {
        given(hospitalService.exists(HOSPITAL_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getHospitalReviews(HOSPITAL_ID, 1, 20))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(HospitalErrorCode.HOSPITAL_NOT_FOUND));
    }

    @Test
    @DisplayName("작성자는 리뷰의 평점과 내용을 수정할 수 있다")
    void update_author_succeeds() {
        Review review = Review.create(
                RESERVATION_ID,
                HOSPITAL_ID,
                MEMBER_ID,
                new BigDecimal("4.0"),
                "기존 내용"
        );
        given(reviewRepository.findByIdForUpdate(100L)).willReturn(Optional.of(review));

        ReviewResponse response = service.update(
                MEMBER_ID,
                100L,
                new ReviewRequest(new BigDecimal("4.5"), "수정한 내용")
        );

        assertThat(response.rating()).isEqualByComparingTo("4.5");
        assertThat(response.content()).isEqualTo("수정한 내용");
        verify(reviewRepository, never()).saveAndFlush(review);
    }

    @Test
    @DisplayName("작성자가 아닌 회원은 리뷰를 수정할 수 없다")
    void update_notAuthor_throwsForbidden() {
        Review review = Review.create(
                RESERVATION_ID,
                HOSPITAL_ID,
                2L,
                new BigDecimal("4.0"),
                "기존 내용"
        );
        given(reviewRepository.findByIdForUpdate(100L)).willReturn(Optional.of(review));

        assertThatThrownBy(() -> service.update(
                MEMBER_ID,
                100L,
                new ReviewRequest(new BigDecimal("4.5"), "수정한 내용")
        )).isInstanceOfSatisfying(ServiceException.class, e ->
                assertThat(e.getErrorCode())
                        .isEqualTo(ReviewErrorCode.NOT_REVIEW_AUTHOR));
    }

    @Test
    @DisplayName("존재하지 않는 리뷰는 수정할 수 없다")
    void update_reviewNotFound_throwsNotFound() {
        given(reviewRepository.findByIdForUpdate(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(
                MEMBER_ID,
                100L,
                new ReviewRequest(new BigDecimal("4.5"), "수정한 내용")
        )).isInstanceOfSatisfying(ServiceException.class, e ->
                assertThat(e.getErrorCode())
                        .isEqualTo(ReviewErrorCode.REVIEW_NOT_FOUND));
    }

    @Test
    @DisplayName("작성자는 리뷰를 Hard Delete할 수 있다")
    void delete_author_succeeds() {
        Review review = Review.create(
                RESERVATION_ID,
                HOSPITAL_ID,
                MEMBER_ID,
                new BigDecimal("4.0"),
                "삭제할 내용"
        );
        given(reviewRepository.findByIdForUpdate(100L)).willReturn(Optional.of(review));

        service.delete(MEMBER_ID, 100L);

        verify(reviewRepository).delete(review);
        verify(reservationService, never())
                .claimReviewOpportunity(any(), any(), any());
    }

    @Test
    @DisplayName("작성자가 아닌 회원은 리뷰를 삭제할 수 없다")
    void delete_notAuthor_throwsForbidden() {
        Review review = Review.create(
                RESERVATION_ID,
                HOSPITAL_ID,
                2L,
                new BigDecimal("4.0"),
                "삭제할 내용"
        );
        given(reviewRepository.findByIdForUpdate(100L)).willReturn(Optional.of(review));

        assertThatThrownBy(() -> service.delete(MEMBER_ID, 100L))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ReviewErrorCode.NOT_REVIEW_AUTHOR));
        verify(reviewRepository, never()).delete(any(Review.class));
    }

    @Test
    @DisplayName("존재하지 않는 리뷰는 삭제할 수 없다")
    void delete_reviewNotFound_throwsNotFound() {
        given(reviewRepository.findByIdForUpdate(100L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(MEMBER_ID, 100L))
                .isInstanceOfSatisfying(ServiceException.class, e ->
                        assertThat(e.getErrorCode())
                                .isEqualTo(ReviewErrorCode.REVIEW_NOT_FOUND));
        verify(reviewRepository, never()).delete(any(Review.class));
    }

    private void givenReviewableReservation(PaymentStatus paymentStatus) {
        given(reservationService.exists(RESERVATION_ID)).willReturn(true);
        given(reservationService.findHospitalIdForOwner(RESERVATION_ID, MEMBER_ID))
                .willReturn(Optional.of(HOSPITAL_ID));
        given(paymentQueryService.findStatusByReservationIdForUpdate(RESERVATION_ID))
                .willReturn(Optional.of(paymentStatus));
        given(reservationService.claimReviewOpportunity(
                RESERVATION_ID,
                MEMBER_ID,
                NOW
        )).willReturn(1);
    }

    private ReviewRequest request() {
        return new ReviewRequest(new BigDecimal("4.5"), "친절했어요.");
    }
}
