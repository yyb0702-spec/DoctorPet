package com.doctorpet.domain.notification.repository;

// 알림 저장·조회 리포지토리. 수신자별 최신순 페이징과 읽음여부 필터(read_at NULL/NOT NULL)를 제공한다.

import com.doctorpet.domain.notification.entity.Notification;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByMemberId(Long memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndReadAtIsNull(Long memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndReadAtIsNotNull(Long memberId, Pageable pageable);

    /*
      개별 알림 읽음 처리(#39)의 동시성 보호(PR #87 P2). Notification에는 @Version이 없어 findById 후 엔티티의
      read_at NULL 검사만으로는 두 트랜잭션이 같은 미읽음 알림을 동시에 읽고 서로 다른 시각을 기록해 최초 시각이
      덮어써질 수 있다. WHERE read_at IS NULL 조건부 UPDATE로 최초 1회 기록을 원자화하고, member_id 조건을 함께
      걸어 소유자 외 갱신을 막는다(호출부 소유권 검사에 대한 방어적 재확인). 갱신 0건은 "이미 읽음"이므로 호출부가
      멱등 200으로 처리한다. JPQL bulk UPDATE는 @LastModifiedDate(updatedAt)를 우회하므로 updatedAt도 같은
      서울 기준 시각으로 명시 갱신한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
               set n.readAt = :now,
                   n.updatedAt = :now
             where n.id = :id
               and n.memberId = :memberId
               and n.readAt is null
            """)
    int markReadIfUnread(
            @Param("id") Long id,
            @Param("memberId") Long memberId,
            @Param("now") LocalDateTime now
    );
}
