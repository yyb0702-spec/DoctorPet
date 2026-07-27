package com.doctorpet.domain.member.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 회원. SA §4 members 테이블 스펙을 따른다.
 * 탈퇴는 Soft Delete(deleted_at)이며, 활성 회원({@code deleted_at IS NULL}) 기준으로만
 * 조회되도록 {@link SQLRestriction}을 적용한다(SA §6-3).
 */
@Getter
@Entity
@Table(name = "members")
@SQLRestriction("deleted_at is null")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Column(name = "hospital_id")
    private Long hospitalId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private Member(String email, String password, String nickname, MemberRole role, Long hospitalId) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.role = role;
        this.hospitalId = hospitalId;
    }

    /**
     * 보호자(GUARDIAN) 회원가입. 병원 스태프(HOSPITAL_STAFF)는 회원가입 대상이 아니라
     * 제휴 병원 시드 데이터로 생성된다(SA §6-2) — 이 팩토리로 만들 수 없다.
     */
    public static Member createGuardian(String email, String password, String nickname) {
        return new Member(email, password, nickname, MemberRole.GUARDIAN, null);
    }
}
