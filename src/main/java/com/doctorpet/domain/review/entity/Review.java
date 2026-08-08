package com.doctorpet.domain.review.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reviews",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reviews_reservation_id",
                columnNames = "reservation_id"
        ),
        check = @CheckConstraint(
                name = "ck_reviews_rating",
                constraint = "rating >= 1.0 AND rating <= 5.0 AND MOD(rating * 10, 5) = 0"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    private Review(
            Long reservationId,
            Long hospitalId,
            Long memberId,
            BigDecimal rating,
            String content
    ) {
        this.reservationId = reservationId;
        this.hospitalId = hospitalId;
        this.memberId = memberId;
        this.rating = rating;
        this.content = content;
    }

    public static Review create(
            Long reservationId,
            Long hospitalId,
            Long memberId,
            BigDecimal rating,
            String content
    ) {
        return new Review(reservationId, hospitalId, memberId, rating, content);
    }
}
