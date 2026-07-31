package com.doctorpet.domain.member.migration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SchemaMigrationRepository extends JpaRepository<SchemaMigrationRecord, String> {
}
