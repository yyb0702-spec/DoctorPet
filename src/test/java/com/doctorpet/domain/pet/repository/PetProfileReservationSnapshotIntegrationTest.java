package com.doctorpet.domain.pet.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.doctorpet.domain.pet.entity.PetProfile;
import com.doctorpet.domain.pet.entity.PetSpecies;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.global.config.JpaAuditingConfig;
import com.doctorpet.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/**
 * Level 3 — PetProfile Soft Delete가 Reservation의 스냅샷 컬럼(pet_name_snapshot /
 * pet_species_snapshot)을 훼손하지 않는지 검증한다(이슈 #11 "삭제 후 기존 예약·진료 이력은
 * 보존" 체크리스트 대응).
 *
 * 이 테스트가 증명하는 범위 — Reservation.petNameSnapshot/petSpeciesSnapshot은 PetProfile과
 * JPA 연관관계(@ManyToOne 등)가 없는 순수 스냅샷 컬럼이다. 예약 생성 시에는
 * ReservationService가 소유한 활성 PetProfile을 조회해 서버 값으로 스냅샷을 만들고, 생성
 * 이후 pet_profiles.deleted_at을 변경해도 reservations에는 FK/cascade가 없어 값이 보존된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        JpaAuditingConfig.class,
        QuerydslConfig.class
})
class PetProfileReservationSnapshotIntegrationTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long HOSPITAL_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long PAYMENT_METHOD_ID = 300L;

    @Autowired
    private PetProfileRepository petProfileRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("PetProfile을 Soft Delete해도 이미 생성된 Reservation의 스냅샷 컬럼은 그대로 남는다")
    void reservationSnapshot_survivesPetProfileSoftDelete() {
        PetProfile pet = petProfileRepository.saveAndFlush(
                PetProfile.create(MEMBER_ID, "초코", PetSpecies.DOG, 3, new BigDecimal("5.4"), true));

        Reservation reservation = reservationRepository.saveAndFlush(
                Reservation.request(
                        MEMBER_ID,
                        pet.getId(),
                        HOSPITAL_ID,
                        SLOT_ID,
                        PAYMENT_METHOD_ID,
                        pet.getName(),
                        pet.getSpecies().name(),
                        LocalDateTime.now()
                ));
        Long reservationId = reservation.getId();
        entityManager.clear();

        PetProfile loaded = petProfileRepository.findById(pet.getId()).orElseThrow();
        loaded.markDeleted(LocalDateTime.now());
        petProfileRepository.saveAndFlush(loaded);
        entityManager.clear();

        // Pet 쪽은 @SQLRestriction("deleted_at is null")으로 조회에서 제외된다.
        assertThat(petProfileRepository.findById(pet.getId())).isEmpty();

        // Reservation은 PetProfile과 연관관계·제약이 전혀 없으므로 스냅샷이 그대로 살아있어야 한다.
        Reservation reloaded = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(reloaded.getPetNameSnapshot()).isEqualTo("초코");
        assertThat(reloaded.getPetSpeciesSnapshot()).isEqualTo("DOG");
    }

    @Test
    @DisplayName("PetProfile 최대 길이 이름 255자는 Reservation 스냅샷에도 손실 없이 저장된다")
    void reservationSnapshot_supportsPetNameMaxLength() {
        String maxLengthName = "가".repeat(255);
        PetProfile pet = petProfileRepository.saveAndFlush(
                PetProfile.create(
                        MEMBER_ID,
                        maxLengthName,
                        PetSpecies.CAT,
                        4,
                        new BigDecimal("4.2"),
                        false
                )
        );

        Reservation reservation = reservationRepository.saveAndFlush(
                Reservation.request(
                        MEMBER_ID,
                        pet.getId(),
                        HOSPITAL_ID,
                        SLOT_ID + 1,
                        PAYMENT_METHOD_ID,
                        pet.getName(),
                        pet.getSpecies().name(),
                        LocalDateTime.now()
                )
        );
        Long reservationId = reservation.getId();
        entityManager.clear();

        Reservation reloaded = reservationRepository.findById(reservationId)
                .orElseThrow();
        assertThat(reloaded.getPetNameSnapshot())
                .hasSize(255)
                .isEqualTo(maxLengthName);
    }
}
