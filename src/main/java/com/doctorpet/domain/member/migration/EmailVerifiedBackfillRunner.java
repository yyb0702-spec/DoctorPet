package com.doctorpet.domain.member.migration;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/*
  리뷰 지적 P1 대응 — email_verified 컬럼을 nullable=false, DEFAULT 0으로 추가하면서(Member.java),
  ddl-auto=update가 이미 존재하던 members 행에도 전부 false를 채운다. AuthService.login()은
  이 값이 false면 역할과 무관하게 로그인을 차단하므로, 이 컬럼이 생기기 전에 가입한 기존
  회원(보호자·병원 스태프 전원)이 배포 즉시 로그인하지 못하게 된다 — 이메일 인증이라는
  개념 자체가 없던 시절에 가입한 사람들에게 소급 적용하는 셈이라 정책적으로도 불합리하다.

  그래서 기존 회원은 true로 백필(grandfather)하고, 이 배포 이후의 신규 가입자만 실제로
  인증을 거치게 한다. Flyway/Liquibase가 없는 프로젝트라(ddl-auto=update로만 스키마를
  관리) "배포 시 한 번만" 실행돼야 하는 이 작업을 마이그레이션 도구 대신 앱 부팅 시점의
  ApplicationRunner로 구현한다.

  반드시 "한 번만" 실행돼야 한다는 점이 핵심이다 — 백필 쿼리 자체(UPDATE ... WHERE
  email_verified = false)는 멱등하지만, 마커 없이 재부팅마다 실행하면 이 배포 이후 정상적으로
  가입해서 "아직 인증 메일을 안 열어본" 신규 회원까지 매번 인증 완료로 되돌려버린다. 그래서
  실행 여부를 휘발성 없는 저장소(DB, schema_migrations)에 영구 기록한다 — Redis 같은 캐시성
  저장소는 운영 중 플러시될 수 있어 이 용도로는 부적합하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "member.email-verified-backfill",
        name = "enabled",
        havingValue = "true"
)
public class EmailVerifiedBackfillRunner implements ApplicationRunner {

    private static final String MIGRATION_KEY = "email_verified_backfill_v1";

    private final SchemaMigrationRepository schemaMigrationRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (schemaMigrationRepository.existsById(MIGRATION_KEY)) {
            return;
        }

        // Member 엔티티(JPQL)가 아니라 네이티브 쿼리로 직접 실행한다 — 이건 도메인 로직이 아니라
        // "컬럼이 생기기 전 존재하던 행"이라는 스키마 마이그레이션 시점의 일회성 데이터 보정이다.
        int updatedCount = entityManager
                .createNativeQuery("update members set email_verified = true where email_verified = false")
                .executeUpdate();

        // saveAndFlush로 이 메서드가 끝나기 전에 마커를 실제로 DB에 내보낸다 — save()만 쓰면
        // 트랜잭션 커밋 시점까지 INSERT가 지연될 수 있고, 호출자가 flush 없이 영속성 컨텍스트를
        // clear()하면(테스트 등) 마커 저장 자체가 유실될 수 있다.
        schemaMigrationRepository.saveAndFlush(new SchemaMigrationRecord(MIGRATION_KEY));

        log.info(
                "email_verified 백필 완료(리뷰 지적 P1 대응): 기존 회원 {}명을 인증 완료 상태로 전환했습니다.",
                updatedCount
        );
    }
}
