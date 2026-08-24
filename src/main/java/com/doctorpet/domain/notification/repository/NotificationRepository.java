package com.doctorpet.domain.notification.repository;

// 알림 저장·조회 리포지토리. 수신자(recipient_type + recipient_id)별 최신순 페이징과 읽음여부 필터(read_at NULL/NOT NULL)를
// 제공한다(고도화 3.10). 병원 수신은 병원 단위 공유이므로 recipient_id=hospitalId 한 축으로 조회·읽음한다.

import com.doctorpet.domain.notification.entity.Notification;
import com.doctorpet.domain.notification.entity.status.NotificationRecipientType;
import com.doctorpet.domain.notification.entity.status.NotificationResourceType;
import com.doctorpet.domain.notification.entity.status.NotificationType;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientTypeAndRecipientId(
            NotificationRecipientType recipientType, Long recipientId, Pageable pageable);

    Page<Notification> findByRecipientTypeAndRecipientIdAndReadAtIsNull(
            NotificationRecipientType recipientType, Long recipientId, Pageable pageable);

    Page<Notification> findByRecipientTypeAndRecipientIdAndReadAtIsNotNull(
            NotificationRecipientType recipientType, Long recipientId, Pageable pageable);

    // 미읽음 개수. 배지 표시용으로 목록을 페이징하지 않고 read_at NULL 건수만 센다(#134, 수신자 기준).
    long countByRecipientTypeAndRecipientIdAndReadAtIsNull(
            NotificationRecipientType recipientType, Long recipientId);

    // 특정 리소스에 대해 같은 유형의 알림이 이미 저장됐는지 판별한다(PR #139). 결제 고도화 3.6에서 "결제 확인 중"
    // 안내(RECONCILE_STUCK)의 멱등 빠른 경로로 쓴다 — 회원 수신(PAYMENT_PENDING)이라 member_id가 채워져 있어 이
    // 조건으로 조회한다. 원자적 1건 보장은 dedup_key UNIQUE가 맡고, 이 조회는 흔한 반복 사이클을 값싸게 거른다.
    boolean existsByMemberIdAndTypeAndResourceTypeAndResourceId(
            Long memberId,
            NotificationType type,
            NotificationResourceType resourceType,
            Long resourceId
    );

    /*
      개별 알림 읽음 처리(#39)의 동시성 보호(PR #87 P2). Notification에는 @Version이 없어 findById 후 엔티티의
      read_at NULL 검사만으로는 두 트랜잭션이 같은 미읽음 알림을 동시에 읽고 서로 다른 시각을 기록해 최초 시각이
      덮어써질 수 있다. 병원 단위 공유에서는 서로 다른 스태프가 같은 알림을 동시에 읽는 것이 정상 경로라 이 보호가 더
      중요하다. WHERE read_at IS NULL 조건부 UPDATE로 최초 1회 기록을 원자화하고, 수신자(recipient_type,
      recipient_id) 조건을 함께 걸어 소유 수신자 외 갱신을 막는다(호출부 소유권 검사에 대한 방어적 재확인). 갱신
      0건은 "이미 읽음"이므로 호출부가 멱등 200으로 처리한다. JPQL bulk UPDATE는 @LastModifiedDate(updatedAt)를
      우회하므로 updatedAt도 같은 서울 기준 시각으로 명시 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
               set n.readAt = :now,
                   n.updatedAt = :now
             where n.id = :id
               and n.recipientType = :recipientType
               and n.recipientId = :recipientId
               and n.readAt is null
            """)
    int markReadIfUnread(
            @Param("id") Long id,
            @Param("recipientType") NotificationRecipientType recipientType,
            @Param("recipientId") Long recipientId,
            @Param("now") LocalDateTime now
    );

    /*
      모두 읽음 처리의 bulk UPDATE. markReadIfUnread에서 id 조건만 뺀 형태로, 수신자의 미읽음 전체를
      WHERE read_at IS NULL 조건부 UPDATE로 한 번에 읽음 처리한다. 갱신 0건은 "이미 전부 읽음"이므로 호출부가
      멱등 처리한다. JPQL bulk UPDATE는 @LastModifiedDate(updatedAt)를 우회하므로 updatedAt도 같은 서울 기준
      시각으로 명시 갱신한다(markReadIfUnread와 동일 이유).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
               set n.readAt = :now,
                   n.updatedAt = :now
             where n.recipientType = :recipientType
               and n.recipientId = :recipientId
               and n.readAt is null
            """)
    int markAllReadForRecipient(
            @Param("recipientType") NotificationRecipientType recipientType,
            @Param("recipientId") Long recipientId,
            @Param("now") LocalDateTime now
    );

    /*
      전체 삭제. 수신자(recipient_type, recipient_id)의 알림을 하드 삭제한다 — 알림은 감사 기록이 아니라
      휘발성 UI 항목이라 소프트 삭제 컬럼을 두지 않는다(감사는 reservation_histories·payments가 담당).
      병원 수신은 병원 단위 공유라 read-all과 같은 모델로 병원 전체 알림을 지운다. 삭제 0건은 "이미 비어
      있음"이므로 호출부가 멱등 처리한다.
    */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from Notification n
             where n.recipientType = :recipientType
               and n.recipientId = :recipientId
            """)
    int deleteAllForRecipient(
            @Param("recipientType") NotificationRecipientType recipientType,
            @Param("recipientId") Long recipientId
    );
}
