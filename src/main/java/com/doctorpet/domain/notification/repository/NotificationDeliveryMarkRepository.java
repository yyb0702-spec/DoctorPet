package com.doctorpet.domain.notification.repository;

import com.doctorpet.domain.notification.entity.NotificationDeliveryMark;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryMarkRepository extends JpaRepository<NotificationDeliveryMark, Long> {

    boolean existsByDedupKey(String dedupKey);
}
