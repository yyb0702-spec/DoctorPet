package com.doctorpet.domain.pet.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Level 3 — pet_profiles 테이블 DDL·제약·조회 통합 검증(docs/testing/verification-guide.md).
 * Mockito 슬라이스(PetServiceTest)는 리포지토리를 목으로 대체하므로, {@code @SQLRestriction
 * ("deleted_at is null")}이 실제로 Hibernate 쿼리에 적용되는지는 검증하지 못한다 — 이 클래스가
 * 실제 MySQL에 연결해 그 부분만 별도로 확인한다(로컬 application-local.yml 또는 CI services
 * 컨테이너(MySQL 8) 필요, H2 미의존이라 replace=NONE).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        QuerydslConfig.class
})
class PetProfileDdlIntegrationTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long OTHER_MEMBER_ID = 2L;

    @Autowired
    private PetProfileRepository petProfileRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("species enum이 문자열로 저장·조회되고 전체 컬럼이 매핑대로 영속화된다")
    void speciesEnumAndColumns_persistCorrectly() {
        PetProfile saved = petProfileRepository.saveAndFlush(
                PetProfile.create(MEMBER_ID, "짹짹이", PetSpecies.BIRD, 3, new BigDecimal("0.4"), false));
        entityManager.clear();

        PetProfile reloaded = petProfileRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(reloaded.getName()).isEqualTo("짹짹이");
        assertThat(reloaded.getSpecies()).isEqualTo(PetSpecies.BIRD);
        assertThat(reloaded.getAge()).isEqualTo(3);
        assertThat(reloaded.getWeight()).isEqualByComparingTo("0.4");
        assertThat(reloaded.getNeutered()).isFalse();
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findAllByMemberIdOrderByIdAsc는 해당 회원 소유 프로필만 등록 순서대로 반환한다")
    void findAllByMemberIdOrderByIdAsc_scopesToOwnerInOrder() {
        PetProfile first = petProfileRepository.saveAndFlush(
                PetProfile.create(MEMBER_ID, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true));
        PetProfile second = petProfileRepository.saveAndFlush(
                PetProfile.create(MEMBER_ID, "나비", PetSpecies.CAT, 2, new BigDecimal("3.2"), false));
        petProfileRepository.saveAndFlush(
                PetProfile.create(OTHER_MEMBER_ID, "뭉치", PetSpecies.DOG, 5, new BigDecimal("8.0"), true));
        entityManager.clear();

        List<PetProfile> myPets = petProfileRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID);

        assertThat(myPets).extracting(PetProfile::getId)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("Soft Delete(deleted_at) 후에는 findById·findAllByMemberIdOrderByIdAsc 모두 제외된다(@SQLRestriction)")
    void softDeletedPet_isExcludedFromActiveQueries() {
        PetProfile saved = petProfileRepository.saveAndFlush(
                PetProfile.create(MEMBER_ID, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true));
        Long petId = saved.getId();

        PetProfile loaded = petProfileRepository.findById(petId).orElseThrow();
        loaded.markDeleted(LocalDateTime.now());
        petProfileRepository.saveAndFlush(loaded);
        entityManager.clear();

        assertThat(petProfileRepository.findById(petId)).isEmpty();
        assertThat(petProfileRepository.findAllByMemberIdOrderByIdAsc(MEMBER_ID)).isEmpty();
    }

    @Test
    @DisplayName("weight 컬럼은 DECIMAL(5,2) 정밀도 그대로 저장·조회된다(정수 3자리·소수 2자리 경계값)")
    void weightColumn_persistsAtDeclaredPrecisionAndScale() {
        PetProfile saved = petProfileRepository.saveAndFlush(
                PetProfile.create(MEMBER_ID, "초코", PetSpecies.DOG, 3, new BigDecimal("999.99"), true));
        entityManager.clear();

        PetProfile reloaded = petProfileRepository.findById(saved.getId()).orElseThrow();

        // API 레벨 @Digits(integer=3, fraction=2)와 정확히 같은 범위라 반올림 없이 그대로 남아야
        // POST 응답과 이후 GET 응답의 weight 값이 어긋나지 않는다.
        assertThat(reloaded.getWeight()).isEqualByComparingTo("999.99");
    }

    @Test
    @DisplayName("image_url 컬럼은 등록 시 null이며 update() 이후 실제 MySQL 저장·재조회 시 값이 그대로 유지된다")
    void imageUrlColumn_startsNullThenPersistsAndReloadsAfterUpdate() {
        PetProfile saved = petProfileRepository.saveAndFlush(
                PetProfile.create(MEMBER_ID, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true));
        entityManager.clear();

        // 등록 시점에는 값이 없다(PetProfile 주석의 계약) — Hibernate가 만든 실제 컬럼이 NOT NULL이
        // 아니라는 것도 이 조회가 예외 없이 통과하는 것으로 함께 확인된다.
        PetProfile beforeUpdate = petProfileRepository.findById(saved.getId()).orElseThrow();
        assertThat(beforeUpdate.getImageUrl()).isNull();

        // saved.getId()의 자릿수에 따라 prefix 길이가 달라질 수 있어(자동증가 PK), 패딩 길이를
        // prefix 길이 기준으로 계산해 정확히 2048자를 맞춘다(하드코딩된 매직넘버로 인한 오차 방지).
        String prefix = "https://doctorpet-bucket.s3.ap-northeast-2.amazonaws.com/pets/%d/"
                .formatted(saved.getId());
        String imageUrl = prefix + "a".repeat(2048 - prefix.length());
        beforeUpdate.update(null, null, null, null, null, imageUrl);
        petProfileRepository.saveAndFlush(beforeUpdate);
        entityManager.clear();

        PetProfile afterUpdate = petProfileRepository.findById(saved.getId()).orElseThrow();

        // VARCHAR(2048) 경계값(정확히 2048자)이 잘리거나 예외 없이 그대로 저장·조회된다.
        assertThat(afterUpdate.getImageUrl()).hasSize(2048).isEqualTo(imageUrl);
    }
}
