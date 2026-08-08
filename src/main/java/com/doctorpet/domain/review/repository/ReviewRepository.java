package com.doctorpet.domain.review.repository;

import com.doctorpet.domain.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByReservationId(Long reservationId);

    Page<Review> findByHospitalId(Long hospitalId, Pageable pageable);

    @Query("""
            select avg(r.rating) as averageRating, count(r) as reviewCount
            from Review r
            where r.hospitalId = :hospitalId
            """)
    ReviewRatingSummaryProjection findRatingSummaryByHospitalId(Long hospitalId);
}
