package com.doctorpet.domain.pet.repository;

import com.doctorpet.domain.pet.entity.PetProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {

    // 목록 조회는 등록 순서(id 오름차순)로 고정한다 — 화면에서 매번 순서가 바뀌지 않도록.
    List<PetProfile> findAllByMemberIdOrderByIdAsc(Long memberId);

    Optional<PetProfile> findByIdAndMemberId(Long id, Long memberId);
}
