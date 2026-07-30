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
 * JPA 연관관계(@ManyToOne 등)가 전혀 없는 순수 String 컬럼이고(ReservationService.request()도
 * PetProfileRepository를 조회하지 않고 클라이언트가 보낸 ReservationRequest 값을 그대로
 * 옮겨 담는다), pet_profiles.deleted_at을 세팅하는 것은 UPDATE 한 줄일 뿐 reservations
 * 테이블에는 어떤 FK/cascade도 없다. 따라서 이 테스트는 "Pet 도메인의 Soft Delete 구현이
 * 실수로 물리 삭제·cascade를 일으켜 다른 도메인 데이터를 훼손하지 않는지"를 검증하는 것이지,
 * "예약 상세 조회 API가 스냅샷을 반환하는지"는 검증하지 않는다 — 그건 ReservationResponse가
 * 오늘 그 두 필드를 아예 응답에 노출하지 않아 Reservation 도메인 쪽 변경 없이는 API 레벨로
 * 증명할 수 없다(PR 리뷰 대응 코멘트 참고).
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
}
