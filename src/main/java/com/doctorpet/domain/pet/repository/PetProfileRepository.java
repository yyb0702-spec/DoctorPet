package com.doctorpet.domain.pet.repository;

import com.doctorpet.domain.pet.entity.PetProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PetProfileRepository extends JpaRepository<PetProfile, Long> {
}
