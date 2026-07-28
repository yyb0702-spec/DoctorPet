package com.doctorpet.domain.pet.entity;

import com.doctorpet.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/*
  반려동물 프로필. SA §4 pet_profiles 테이블 스펙을 따른다.
  member_id는 도메인 경계를 넘는 참조라 SA §4(패키지 구조 절)의 가이드에 따라 JPA 연관관계
  (@ManyToOne) 없이 순수 Long 값으로 둔다 — 무결성은 애플리케이션 계층(인증 주체 기반 소유권
  검증)에서 보장한다.
  삭제는 Soft Delete(deleted_at)이며, 활성 프로필({@code deleted_at IS NULL}) 기준으로만
  조회되도록 {@link SQLRestriction}을 적용한다. 과거 예약은 reservations에 이름·종 스냅샷을
  남겨 이력을 보존한다(SA §4).
 */
@Getter
@Entity
@Table(name = "pet_profiles")
@SQLRestriction("deleted_at is null")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PetProfile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PetSpecies species;

    @Column(nullable = false)
    private Integer age;

    @Column(nullable = false)
    private BigDecimal weight;

    @Column(nullable = false)
    private Boolean neutered;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private PetProfile(
            Long memberId,
            String name,
            PetSpecies species,
            Integer age,
            BigDecimal weight,
            Boolean neutered
    ) {
        this.memberId = memberId;
        this.name = name;
        this.species = species;
        this.age = age;
        this.weight = weight;
        this.neutered = neutered;
    }

    /** 반려동물 프로필 등록. 보호자 1인당 등록 개수 제한은 없다(A 도메인 결정 #4). */
    public static PetProfile create(
            Long memberId,
            String name,
            PetSpecies species,
            Integer age,
            BigDecimal weight,
            Boolean neutered
    ) {
        return new PetProfile(memberId, name, species, age, weight, neutered);
    }

    /*
     * 반려동물 프로필 수정. SA §8-2 — 등록과 동일한 5개 필드를 전체 교체한다(부분 수정 아님).
     * memberId(소유자)는 수정 대상이 아니다 — 소유권 이전은 지원하지 않는다.
     * 과거 예약에는 스냅샷이 별도로 남아 있어(SA §4) 이 수정이 과거 이력에 영향을 주지 않는다.
     */
    public void update(
            String name,
            PetSpecies species,
            Integer age,
            BigDecimal weight,
            Boolean neutered
    ) {
        this.name = name;
        this.species = species;
        this.age = age;
        this.weight = weight;
        this.neutered = neutered;
    }

    /*
     * 반려동물 프로필 삭제. SA §8-2 — 물리 삭제하지 않고 deleted_at만 채운다(Soft Delete).
     * 이후 findById 등 조회는 클래스 레벨 @SQLRestriction("deleted_at is null")에 의해
     * 이 프로필을 자동으로 제외한다. 과거 예약에는 스냅샷이 별도로 남아 있어(SA §4) 이력에는
     * 영향이 없다.
     */
    public void markDeleted(LocalDateTime now) {
        this.deletedAt = now;
    }
}
