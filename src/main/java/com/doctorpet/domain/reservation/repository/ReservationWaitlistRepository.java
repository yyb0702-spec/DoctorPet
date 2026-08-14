package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.entity.ReservationWaitlist;
import com.doctorpet.domain.reservation.entity.status.ReservationWaitlistStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    /**
     * 종료된 기존 행은 UNIQUE(member_id, slot_id)를 유지한 채 다시 WAITING으로 사용한다.
     * created_at을 애플리케이션 기준 시각으로 새로 기록해 재등록은 FIFO의 맨 뒤에 선다. 슬롯도 아직
     * RESERVED일 때만 갱신하므로 반환과 등록이 경합해 OPEN 슬롯에 WAITING 행이 남지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update reservation_waitlists waitlist
            join reservation_slots slot on slot.id = waitlist.slot_id
               set waitlist.status = 'WAITING',
                   waitlist.offered_at = null,
                   waitlist.offer_expires_at = null,
                   waitlist.responded_at = null,
                   waitlist.canceled_at = null,
                   waitlist.created_at = :reRegisteredAt,
                   waitlist.updated_at = :reRegisteredAt,
                   waitlist.version = waitlist.version + 1
             where waitlist.member_id = :memberId
               and waitlist.slot_id = :slotId
               and waitlist.status in ('CANCELED', 'REJECTED', 'EXPIRED')
               and slot.status = 'RESERVED'
            """, nativeQuery = true)
    int reactivateTerminalIfSlotReserved(
            @Param("memberId") Long memberId,
            @Param("slotId") Long slotId,
            @Param("reRegisteredAt") LocalDateTime reRegisteredAt
    );

    Optional<ReservationWaitlist> findByMemberIdAndSlotId(Long memberId, Long slotId);

    List<ReservationWaitlist> findAllByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);

    List<ReservationWaitlist> findByMemberIdAndStatusIn(
            Long memberId,
            List<ReservationWaitlistStatus> statuses
    );

    List<ReservationWaitlist> findBySlotIdAndStatusOrderByCreatedAtAscIdAsc(
            Long slotId,
            ReservationWaitlistStatus status
    );

    boolean existsBySlotIdAndStatus(Long slotId, ReservationWaitlistStatus status);

    /**
     * 승급 마감(슬롯 시작 4시간 전) 이후에는 새 OFFERED를 만들 수 없으므로, 남아 있는 WAITING을
     * 시스템 취소한다. 슬롯 상태도 RESERVED일 때만 갱신해 이미 반환된 슬롯의 대기열을 건드리지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update reservation_waitlists waitlist
            join reservation_slots slot on slot.id = waitlist.slot_id
               set waitlist.status = 'CANCELED',
                   waitlist.canceled_at = :canceledAt,
                   waitlist.updated_at = :canceledAt,
                   waitlist.version = waitlist.version + 1
             where waitlist.slot_id = :slotId
               and waitlist.status = 'WAITING'
               and slot.status = 'RESERVED'
               and slot.start_at <= :promotionDeadline
            """, nativeQuery = true)
    int cancelWaitingIfPromotionDeadlinePassed(
            @Param("slotId") Long slotId,
            @Param("promotionDeadline") LocalDateTime promotionDeadline,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    /**
     * 휴업·폐업 같은 병원 운영 상태 변경에서는 새 예약 기회를 제안하지 않고 활성 대기열을 종료한다.
     * OFFERED도 함께 취소해야 보호자가 수락할 수 없는 제안으로 슬롯이 계속 점유되지 않는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update reservation_waitlists
               set status = 'CANCELED',
                   canceled_at = :canceledAt,
                   updated_at = :canceledAt,
                   version = version + 1
             where slot_id = :slotId
               and status in ('WAITING', 'OFFERED')
            """, nativeQuery = true)
    int cancelActiveForBusinessStatusChange(
            @Param("slotId") Long slotId,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    /** 휴업·폐업 처리 시 확정 예약과 무관하게 병원의 활성 대기열 슬롯을 찾는다. */
    @Query("""
            select distinct waitlist.slotId
              from ReservationWaitlist waitlist, ReservationSlot slot
             where waitlist.slotId = slot.id
               and slot.hospitalId = :hospitalId
               and slot.startAt > :now
               and waitlist.status in :statuses
            """)
    List<Long> findActiveSlotIdsByHospitalId(
            @Param("hospitalId") Long hospitalId,
            @Param("statuses") List<ReservationWaitlistStatus> statuses,
            @Param("now") LocalDateTime now
    );

    List<ReservationWaitlist> findByStatusAndOfferExpiresAtLessThanEqualOrderByOfferExpiresAtAscIdAsc(
            ReservationWaitlistStatus status,
            LocalDateTime offerExpiresAt,
            Pageable pageable
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
