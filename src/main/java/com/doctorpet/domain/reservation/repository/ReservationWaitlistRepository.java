package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationWaitlistRepository extends JpaRepository<ReservationWaitlist, Long> {

    boolean existsByMemberIdAndSlotId(Long memberId, Long slotId);

    /**
     * UNIQUE(member_id, slot_id)를 최종 중복 판정으로 사용한다. 사전 exists 조회만으로는 동시에
     * 들어온 두 등록 요청을 막을 수 없으므로, 중복 키는 변경 없는 UPDATE로 처리해 0건을 반환한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into reservation_waitlists
                   (member_id, slot_id, status, created_at, updated_at, version)
            values (:memberId, :slotId, 'WAITING', now(6), now(6), 0)
            on duplicate key update id = id
            """, nativeQuery = true)
    int insertWaitingIfAbsent(
            @Param("memberId") Long memberId,
            @Param("slotId") Long slotId
    );

    Optional<ReservationWaitlist> findByMemberIdAndSlotId(Long memberId, Long slotId);

    List<ReservationWaitlist> findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
            Long slotId,
            ReservationWaitlistStatus status
    );

    List<ReservationWaitlist> findByStatusAndOfferExpiresAtLessThanEqualOrderByOfferExpiresAtAscIdAsc(
            ReservationWaitlistStatus status,
            LocalDateTime offerExpiresAt
    );

    /**
     * 수락 시점의 상태·만료 조건을 DB에서 한 번에 확인한다. 조회 후 엔티티 변경만으로는 만료 배치나
     * 중복 수락 요청과 경합할 수 있으므로, 이 UPDATE가 1건 성공한 경우만 REQUESTED 생성을 이어간다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ReservationWaitlist waitlist
               set waitlist.status = com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.ACCEPTED,
                   waitlist.respondedAt = :respondedAt
             where waitlist.id = :waitlistId
               and waitlist.memberId = :memberId
               and waitlist.status = com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.OFFERED
               and waitlist.offerExpiresAt > :respondedAt
            """)
    int acceptIfActive(
            @Param("waitlistId") Long waitlistId,
            @Param("memberId") Long memberId,
            @Param("respondedAt") LocalDateTime respondedAt
    );
}
