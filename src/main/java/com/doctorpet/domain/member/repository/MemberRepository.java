package com.doctorpet.domain.member.repository;

import com.doctorpet.domain.member.entity.Member;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * Member 엔티티의 {@code @SQLRestriction("deleted_at is null")} 덕분에
     * 이 메서드는 자동으로 활성 회원만 대상으로 중복 여부를 확인한다(SA §6-3).
     */
    boolean existsByEmail(String email);

    /** 로그인용 조회. 같은 이유로 활성 회원만 대상이 된다. */
    Optional<Member> findByEmail(String email);

    /**
     * 로그인 시도(실패 카운트 증가·잠금 판정)에서만 사용하는 비관적 락 조회.
     * 같은 계정으로 동시에 여러 로그인 요청이 들어와도 {@code SELECT ... FOR UPDATE}로 행을 잠가
     * 한 트랜잭션씩 순서대로 처리하게 만든다 — 그냥 {@code findByEmail}로 읽으면 여러 트랜잭션이
     * 모두 같은(옛) failedLoginAttempts 값을 읽어 각자 +1만 저장하는 lost update가 발생해,
     * 실제로는 임계값(5회)에 도달했는데도 잠금이 걸리지 않는 우회가 가능하다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.email = :email")
    Optional<Member> findByEmailForUpdate(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :memberId")
    Optional<Member> findByIdForUpdate(@Param("memberId") Long memberId);
}
