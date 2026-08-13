package com.doctorpet.domain.chat.repository;

import com.doctorpet.domain.chat.entity.ChatMessage;
import com.doctorpet.domain.chat.entity.ChatSenderType;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByReservationIdOrderByCreatedAtAscIdAsc(Long reservationId, Pageable pageable);

    @Query("""
            select m
              from ChatMessage m
             where m.reservationId = :reservationId
               and (m.createdAt > :afterCreatedAt
                    or (m.createdAt = :afterCreatedAt and m.id > :afterMessageId))
             order by m.createdAt asc, m.id asc
            """)
    List<ChatMessage> findAfterCursor(
            @Param("reservationId") Long reservationId,
            @Param("afterCreatedAt") LocalDateTime afterCreatedAt,
            @Param("afterMessageId") Long afterMessageId,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ChatMessage m
               set m.readAt = :now,
                   m.updatedAt = :now
             where m.reservationId = :reservationId
               and m.senderType = :senderType
               and m.readAt is null
            """)
    int markReadByReservationIdAndSenderType(
            @Param("reservationId") Long reservationId,
            @Param("senderType") ChatSenderType senderType,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from ChatMessage m
             where m.createdAt <= :cutoff
            """)
    int deleteExpiredMessages(@Param("cutoff") LocalDateTime cutoff);
}
