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
            select :memberId, slot.id, 'WAITING', now(6), now(6), 0
              from reservation_slots slot
             where slot.id = :slotId
               and slot.status = 'RESERVED'
            on duplicate key update id = reservation_waitlists.id
            """, nativeQuery = true)
    int insertWaitingIfSlotReserved(
            @Param("memberId") Long memberId,
            @Param("slotId") Long slotId
    );

    Optional<ReservationWaitlist> findByMemberIdAndSlotId(Long memberId, Long slotId);

    List<ReservationWaitlist> findAllByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);

    List<ReservationWaitlist> findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
            Long slotId,
            ReservationWaitlistStatus status
    );

    List<ReservationWaitlist> findByStatusAndOfferExpiresAtLessThanEqualOrderByOfferExpiresAtAscIdAsc(
            ReservationWaitlistStatus status,
            LocalDateTime offerExpiresAt
    );

    /**
     * 수락 시점의 상태·만료 조건을 DB에서 한 번에 확인하고 version도 증가시킨다. 이미 OFFERED를 읽은
     * 만료 배치·거절 요청은 flush 시 낙관적 락 충돌로 롤백되어, ACCEPTED 예약 생성 뒤 슬롯이 반환되는
     * 상태 역전을 막는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update ReservationWaitlist waitlist
               set waitlist.status = com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus.ACCEPTED,
                   waitlist.respondedAt = :respondedAt,
                   waitlist.version = waitlist.version + 1
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
