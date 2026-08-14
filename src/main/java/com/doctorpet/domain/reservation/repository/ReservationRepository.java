package com.doctorpet.domain.reservation.repository;

import com.doctorpet.domain.reservation.dto.query.ReservationHistoryAggregate;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.entity.ReservationSlot;
import com.doctorpet.domain.reservation.entity.status.ReservationStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;

public interface ReservationRepository
        extends JpaRepository<Reservation, Long>, ReservationQueryRepository {

    @Query("""
            select r
              from Reservation r
             where r.hospitalId = :hospitalId
               and r.status = :status
               and exists (
                    select 1
                      from ReservationSlot s
                     where s.id = r.slotId
                       and s.startAt > :now
               )
            """)
    List<Reservation> findAllCancelableByHospitalIdAndStatus(
            @Param("hospitalId") Long hospitalId,
            @Param("status") ReservationStatus status,
            @Param("now") LocalDateTime now
    );

    Optional <Reservation> findByIdAndMemberId(
            Long reservationId,
            Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
              from Reservation r
             where r.id = :reservationId
               and r.memberId = :memberId
            """)
    Optional<Reservation> findByIdAndMemberIdForUpdate(
            @Param("reservationId") Long reservationId,
            @Param("memberId") Long memberId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
              from Reservation r
             where r.id = :reservationId
            """)
    Optional<Reservation> findByIdForUpdate(@Param("reservationId") Long reservationId);

    Optional<Reservation> findByIdAndHospitalId(
            Long reservationId,
            Long hospitalId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
              from Reservation r
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
            """)
    Optional<Reservation> findByIdAndHospitalIdForUpdate(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId
    );

    Page<Reservation> findByHospitalIdAndStatus(
            Long hospitalId,
            ReservationStatus status,
            Pageable pageable
    );

    @Query("""
            select new com.doctorpet.domain.reservation.dto.query.ReservationHistoryAggregate(
                    r.memberId,
                    count(r),
                    sum(case when r.status = :completedStatus then 1L else 0L end),
                    sum(case when r.status in :canceledStatuses then 1L else 0L end),
                    sum(case when r.status = :noShowStatus then 1L else 0L end)
            )
              from Reservation r
             where r.memberId in :memberIds
             group by r.memberId
            """)
    List<ReservationHistoryAggregate> findHistoryAggregates(
            @Param("memberIds") Collection<Long> memberIds,
            @Param("completedStatus") ReservationStatus completedStatus,
            @Param("canceledStatuses") Collection<ReservationStatus> canceledStatuses,
            @Param("noShowStatus") ReservationStatus noShowStatus
    );

    long countBySlotId(Long slotId);

    // 회원 탈퇴 전 활성 예약 보유 여부 확인용(SA §6-3, 부록A 확정 — 탈퇴 보류 정책).
    // MemberWithdrawalApplicationService가 ReservationService를 경유해 호출한다.
    boolean existsByMemberIdAndStatusIn(Long memberId, Collection<ReservationStatus> statuses);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :canceledStatus,
                   r.canceledAt = :canceledAt
             where r.id = :reservationId
               and r.memberId = :memberId
               and r.status in (:requestedStatus, :confirmedStatus)
            """)
    int cancelIfAllowed(
            @Param("reservationId") Long reservationId,
            @Param("memberId") Long memberId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("canceledStatus") ReservationStatus canceledStatus,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.reviewedAt = :reviewedAt,
                   r.updatedAt = :reviewedAt
             where r.id = :reservationId
               and r.memberId = :memberId
               and r.reviewedAt is null
               and exists (
                    select 1
                      from Payment p
                     where p.reservationId = r.id
                       and p.status in (
                            com.doctorpet.domain.payment.entity.PaymentStatus.PAID,
                            com.doctorpet.domain.payment.entity.PaymentStatus.OFFLINE_PAID
                       )
               )
            """)
    int claimReviewOpportunity(
            @Param("reservationId") Long reservationId,
            @Param("memberId") Long memberId,
            @Param("reviewedAt") LocalDateTime reviewedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :confirmedStatus,
                   r.confirmedAt = :confirmedAt,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :requestedStatus
               and r.approvalDeadlineAt > :confirmedAt
            """)
    int approveIfRequested(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("confirmedAt") LocalDateTime confirmedAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :rejectedStatus,
                   r.rejectReason = :rejectReason,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :requestedStatus
            """)
    int rejectIfRequested(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("rejectedStatus") ReservationStatus rejectedStatus,
            @Param("rejectReason") String rejectReason,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    // 예약 상태는 CONFIRMED 조건부 UPDATE로 한 번만 전이한다. flush 후 영속성 컨텍스트를
    // clear해 동시 요청이 보유한 오래된 Reservation을 재사용하지 않도록 하며, 호출부는
    // clear 전 필요한 memberId·slotId를 로컬 변수로 보관하거나 전이 후 재조회해야 한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :hospitalCanceledStatus,
                   r.hospitalCancelReason = :reason,
                   r.hospitalCanceledAt = :canceledAt,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :confirmedStatus
               and exists (
                    select 1
                      from ReservationSlot s
                     where s.id = r.slotId
                       and s.startAt > :now
               )
            """)
    int cancelIfConfirmedByHospital(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("hospitalCanceledStatus") ReservationStatus hospitalCanceledStatus,
            @Param("reason") String reason,
            @Param("canceledAt") LocalDateTime canceledAt,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("now") LocalDateTime now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :checkedInStatus,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status in :arrivalStatuses
               and exists (
                    select 1
                      from ReservationSlot s
                     where s.id = r.slotId
                       and s.startAt >= :checkInCutoff
               )
            """)
    int checkInIfAwaitingArrival(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("arrivalStatuses") Collection<ReservationStatus> arrivalStatuses,
            @Param("checkedInStatus") ReservationStatus checkedInStatus,
            @Param("checkInCutoff") LocalDateTime checkInCutoff,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        update Reservation r
           set r.status = :rejectedStatus,
               r.updatedAt = :now,
               r.approvalTimeoutNextRetryAt = null
         where r.id = :reservationId
           and r.status = :requestedStatus
           and r.approvalDeadlineAt <= :now
        """)
    int rejectByTimeoutIfRequested(
            @Param("reservationId") Long reservationId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("rejectedStatus") ReservationStatus rejectedStatus,
            @Param("now") LocalDateTime now
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.approvalTimeoutNextRetryAt = :nextRetryAt
             where r.id = :reservationId
               and r.status = :requestedStatus
            """)
    int deferApprovalTimeoutRetry(
            @Param("reservationId") Long reservationId,
            @Param("requestedStatus") ReservationStatus requestedStatus,
            @Param("nextRetryAt") LocalDateTime nextRetryAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :inTreatmentStatus,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :checkedInStatus
            """)
    int startTreatmentIfCheckedIn(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("checkedInStatus") ReservationStatus checkedInStatus,
            @Param("inTreatmentStatus") ReservationStatus inTreatmentStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :completedStatus,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :inTreatmentStatus
            """)
    int completeTreatmentIfInTreatment(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("inTreatmentStatus") ReservationStatus inTreatmentStatus,
            @Param("completedStatus") ReservationStatus completedStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :noShowStatus,
                   r.noShowAt = :noShowAt,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status in :arrivalStatuses
            """)
    int markNoShowIfAwaitingArrival(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("arrivalStatuses") Collection<ReservationStatus> arrivalStatuses,
            @Param("noShowStatus") ReservationStatus noShowStatus,
            @Param("noShowAt") LocalDateTime noShowAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :pendingStatus,
                   r.noShowPendingAt = :pendingAt,
                   r.updatedAt = :pendingAt
             where r.id = :reservationId
               and r.status = :confirmedStatus
               and exists (
                    select 1
                      from ReservationSlot s
                     where s.id = r.slotId
                       and s.startAt < :pendingCutoff
               )
            """)
    int markAutoNoShowPendingIfConfirmed(
            @Param("reservationId") Long reservationId,
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("pendingStatus") ReservationStatus pendingStatus,
            @Param("pendingAt") LocalDateTime pendingAt,
            @Param("pendingCutoff") LocalDateTime pendingCutoff
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :noShowStatus,
                   r.noShowAt = :noShowAt,
                   r.updatedAt = :noShowAt
             where r.id = :reservationId
               and r.status = :pendingStatus
               and exists (
                    select 1
                      from ReservationSlot s
                     where s.id = r.slotId
                       and s.startAt < :finalCutoff
               )
            """)
    int markAutoNoShowIfPending(
            @Param("reservationId") Long reservationId,
            @Param("pendingStatus") ReservationStatus pendingStatus,
            @Param("noShowStatus") ReservationStatus noShowStatus,
            @Param("noShowAt") LocalDateTime noShowAt,
            @Param("finalCutoff") LocalDateTime finalCutoff
    );

    @Query("""
            select r.id as reservationId,
                   r.status as status,
                   s.startAt as slotStartAt
              from Reservation r, ReservationSlot s
             where s.id = r.slotId
               and ((r.status = :confirmedStatus and s.startAt < :pendingCutoff)
                    or (r.status = :pendingStatus and s.startAt < :finalCutoff))
             order by s.startAt asc, r.id asc
            """)
    List<ReservationNoShowTarget> findAutoNoShowTargets(
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("pendingStatus") ReservationStatus pendingStatus,
            @Param("pendingCutoff") LocalDateTime pendingCutoff,
            @Param("finalCutoff") LocalDateTime finalCutoff,
            Pageable pageable
    );

    @Query("""
            select r.id as reservationId,
                   r.status as status,
                   s.startAt as slotStartAt
              from Reservation r, ReservationSlot s
             where s.id = r.slotId
               and ((r.status = :confirmedStatus and s.startAt < :pendingCutoff)
                    or (r.status = :pendingStatus and s.startAt < :finalCutoff))
               and (s.startAt > :cursorStartAt
                    or (s.startAt = :cursorStartAt and r.id > :cursorId))
             order by s.startAt asc, r.id asc
            """)
    List<ReservationNoShowTarget> findAutoNoShowTargetsAfter(
            @Param("confirmedStatus") ReservationStatus confirmedStatus,
            @Param("pendingStatus") ReservationStatus pendingStatus,
            @Param("pendingCutoff") LocalDateTime pendingCutoff,
            @Param("finalCutoff") LocalDateTime finalCutoff,
            @Param("cursorStartAt") LocalDateTime cursorStartAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = :checkedInStatus,
                   r.updatedAt = :updatedAt
             where r.id = :reservationId
               and r.hospitalId = :hospitalId
               and r.status = :noShowStatus
            """)
    int restoreNoShowIfNoShow(
            @Param("reservationId") Long reservationId,
            @Param("hospitalId") Long hospitalId,
            @Param("noShowStatus") ReservationStatus noShowStatus,
            @Param("checkedInStatus") ReservationStatus checkedInStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );


    @Query("""
        select r
          from Reservation r
         where r.status = :requestedStatus
           and r.approvalDeadlineAt <= :now
           and r.approvalTimeoutNextRetryAt is null
         order by r.approvalDeadlineAt asc, r.id asc
        """)
    List<Reservation> findApprovalTimeoutTargets(
            @Param("requestedStatus") ReservationStatus reservationStatus,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    /** 마감 시각·ID 커서 뒤의 다음 페이지를 조회해 실패 예약의 starvation을 막는다. */
    @Query("""
        select r
          from Reservation r
         where r.status = :requestedStatus
           and r.approvalDeadlineAt <= :now
           and r.approvalTimeoutNextRetryAt is null
           and (
                r.approvalDeadlineAt > :cursorDeadline
                or (
                    r.approvalDeadlineAt = :cursorDeadline
                    and r.id > :cursorId
                )
           )
         order by r.approvalDeadlineAt asc, r.id asc
        """)
    List<Reservation> findApprovalTimeoutTargetsAfter(
            @Param("requestedStatus") ReservationStatus reservationStatus,
            @Param("now") LocalDateTime now,
            @Param("cursorDeadline") LocalDateTime cursorDeadline,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Query("""
        select r from Reservation r
         where r.status = :requestedStatus
           and r.approvalDeadlineAt <= :now
           and r.approvalTimeoutNextRetryAt <= :now
         order by r.approvalDeadlineAt asc, r.id asc
        """)
    List<Reservation> findApprovalTimeoutRetryTargets(
            @Param("requestedStatus") ReservationStatus reservationStatus,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    @Query("""
        select r from Reservation r
         where r.status = :requestedStatus
           and r.approvalDeadlineAt <= :now
           and r.approvalTimeoutNextRetryAt <= :now
           and (r.approvalDeadlineAt > :cursorDeadline
                or (r.approvalDeadlineAt = :cursorDeadline and r.id > :cursorId))
         order by r.approvalDeadlineAt asc, r.id asc
        """)
    List<Reservation> findApprovalTimeoutRetryTargetsAfter(
            @Param("requestedStatus") ReservationStatus reservationStatus,
            @Param("now") LocalDateTime now,
            @Param("cursorDeadline") LocalDateTime cursorDeadline,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );
}
