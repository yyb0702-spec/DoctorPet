package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
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
}
