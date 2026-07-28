package com.doctorpet.domain.pet.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.global.config.JpaAuditingConfig;
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
@Import(JpaAuditingConfig.class)
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
                PetProfile.create(MEMBER_ID, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true));
        entityManager.clear();

        PetProfile reloaded = petProfileRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(reloaded.getName()).isEqualTo("초코");
        assertThat(reloaded.getSpecies()).isEqualTo(PetSpecies.DOG);
        assertThat(reloaded.getAge()).isEqualTo(3);
        assertThat(reloaded.getWeight()).isEqualByComparingTo("5.4");
        assertThat(reloaded.getNeutered()).isTrue();
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
}
