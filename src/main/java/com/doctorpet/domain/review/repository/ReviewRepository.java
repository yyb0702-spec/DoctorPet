package com.doctorpet.domain.review.repository;

import com.doctorpet.domain.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByReservationId(Long reservationId);

    Page<Review> findByHospitalId(Long hospitalId, Pageable pageable);
}
