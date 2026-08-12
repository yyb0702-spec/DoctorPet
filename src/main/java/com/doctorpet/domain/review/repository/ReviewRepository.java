package com.doctorpet.domain.review.repository;

import com.doctorpet.domain.review.entity.Review;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
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

    @Query("""
            select r.hospitalId as hospitalId,
                   avg(r.rating) as averageRating,
                   count(r) as reviewCount,
                   sum(case when r.rating >= 4.0 then 1 else 0 end) as positiveReviewCount,
                   sum(case when r.rating >= 3.0 and r.rating < 4.0 then 1 else 0 end) as neutralReviewCount,
                   sum(case when r.rating < 3.0 then 1 else 0 end) as negativeReviewCount
            from Review r
            where r.hospitalId in :hospitalIds
            group by r.hospitalId
            """)
    List<HospitalReviewStatisticsProjection> findStatisticsByHospitalIds(
            @Param("hospitalIds") Collection<Long> hospitalIds
    );

    @Query(value = """
            SELECT ranked.id AS reviewId,
                   ranked.hospital_id AS hospitalId,
                   ranked.rating AS rating,
                   ranked.content AS content,
                   ranked.created_at AS createdAt
              FROM (
                    SELECT r.id,
                           r.hospital_id,
                           r.rating,
                           r.content,
                           r.created_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY r.hospital_id,
                                            CASE
                                                WHEN r.rating >= 4.0 THEN 'POSITIVE'
                                                WHEN r.rating >= 3.0 THEN 'NEUTRAL'
                                                ELSE 'NEGATIVE'
                                            END
                               ORDER BY r.created_at DESC, r.id DESC
                           ) AS rating_rank
                      FROM reviews r
                     WHERE r.hospital_id IN (:hospitalIds)
                   ) ranked
             WHERE ranked.rating_rank <= 2
             ORDER BY ranked.hospital_id, ranked.created_at DESC, ranked.id DESC
            """, nativeQuery = true)
    List<ReviewExcerptProjection> findLatestExcerptsByHospitalIds(
            @Param("hospitalIds") Collection<Long> hospitalIds
    );
}
