package com.doctorpet.domain.notification.repository;

// 알림 저장·조회 리포지토리. 수신자별 최신순 페이징과 읽음여부 필터(read_at NULL/NOT NULL)를 제공한다.

import com.doctorpet.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByMemberId(Long memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndReadAtIsNull(Long memberId, Pageable pageable);

    Page<Notification> findByMemberIdAndReadAtIsNotNull(Long memberId, Pageable pageable);
}
