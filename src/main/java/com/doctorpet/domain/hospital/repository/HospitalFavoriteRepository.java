package com.doctorpet.domain.hospital.repository;

import com.doctorpet.domain.hospital.entity.HospitalFavorite;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HospitalFavoriteRepository
        extends JpaRepository<HospitalFavorite, Long> {

    @Modifying
    @Query(
            value = """
                    INSERT INTO hospital_favorites
                        (member_id, hospital_id, created_at)
                    VALUES (:memberId, :hospitalId, :createdAt)
                    ON DUPLICATE KEY UPDATE id = id
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("memberId") Long memberId,
            @Param("hospitalId") Long hospitalId,
            @Param("createdAt") LocalDateTime createdAt
    );

    long deleteByMemberIdAndHospitalId(Long memberId, Long hospitalId);

    long deleteAllByMemberId(Long memberId);

    long countByMemberIdAndHospitalId(Long memberId, Long hospitalId);

    @Query("""
            select favorite.hospital.id
            from HospitalFavorite favorite
            where favorite.memberId = :memberId
              and favorite.hospital.id in :hospitalIds
            """)
    List<Long> findHospitalIdsByMemberIdAndHospitalIdIn(
            @Param("memberId") Long memberId,
            @Param("hospitalIds") Collection<Long> hospitalIds
    );

    @EntityGraph(attributePaths = "hospital")
    Page<HospitalFavorite> findByMemberId(
            Long memberId,
            Pageable pageable
    );
}
