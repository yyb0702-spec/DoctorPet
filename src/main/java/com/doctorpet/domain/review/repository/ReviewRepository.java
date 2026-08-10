package com.doctorpet.domain.review.repository;

import com.doctorpet.domain.review.entity.Review;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByReservationId(Long reservationId);

    void deleteByReservationId(Long reservationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
              from Review r
             where r.id = :reviewId
            """)
    Optional<Review> findByIdForUpdate(@Param("reviewId") Long reviewId);

    Page<Review> findByHospitalId(Long hospitalId, Pageable pageable);

    @Query("""
            select avg(r.rating) as averageRating, count(r) as reviewCount
            from Review r
            where r.hospitalId = :hospitalId
            """)
    ReviewRatingSummaryProjection findRatingSummaryByHospitalId(Long hospitalId);
}
