package com.doctorpet.domain.member.migration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
  Flyway/Liquibase 없이 ddl-auto=update로 스키마를 관리하는 이 프로젝트에서, "딱 한 번만
  실행돼야 하는" 데이터 백필(예: email_verified 백필, 리뷰 지적 P1)의 실행 여부를 기록하는
  마커 테이블이다. 백필 자체는 멱등(같은 UPDATE를 여러 번 실행해도 결과가 같음)하지만, 이
  마커가 없으면 재부팅마다 재실행되어 "그때부터 새로 가입해서 아직 인증 안 한 회원"까지
  잘못 인증 완료로 되돌려버릴 수 있다 — 그래서 실행 여부 자체를 DB(휘발성 없는 저장소)에
  영구적으로 남겨야 한다.
 */
@Getter
@Entity
@Table(name = "schema_migrations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchemaMigrationRecord {

    @Id
    @Column(name = "migration_key", length = 100)
    private String migrationKey;

    @Column(name = "applied_at", nullable = false)
    private LocalDateTime appliedAt;

    public SchemaMigrationRecord(String migrationKey) {
        this.migrationKey = migrationKey;
        this.appliedAt = LocalDateTime.now();
    }
}
