package com.doctorpet.domain.chat.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "chat_messages",
        indexes = {
                @Index(name = "idx_chat_messages_reservation_created", columnList = "reservation_id, created_at, id"),
                @Index(name = "idx_chat_messages_reservation_sender_read", columnList = "reservation_id, sender_type, read_at")
        }
)
@AttributeOverride(
        name = "createdAt",
        column = @Column(name = "created_at", nullable = false, updatable = false)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20)
    private ChatSenderType senderType;

    @Column(name = "hospital_id", nullable = false)
    private Long hospitalId;

    // 보호자 또는 실제 발신 병원 스태프의 memberId. 응답에는 노출하지 않는 감사 정보다.
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    private ChatMessage(
            Long reservationId,
            ChatSenderType senderType,
            Long hospitalId,
            Long memberId,
            String body
    ) {
        this.reservationId = reservationId;
        this.senderType = senderType;
        this.hospitalId = hospitalId;
        this.memberId = memberId;
        this.body = body;
    }

    public static ChatMessage create(
            Long reservationId,
            ChatSenderType senderType,
            Long hospitalId,
            Long memberId,
            String body
    ) {
        return new ChatMessage(reservationId, senderType, hospitalId, memberId, body);
    }
}
