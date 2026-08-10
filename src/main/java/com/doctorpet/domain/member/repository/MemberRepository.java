package com.doctorpet.domain.member.repository;

import com.doctorpet.domain.member.entity.Member;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * 비밀번호 재설정 확인, 회원 탈퇴-병원 찜 등록 경합 직렬화에서 공용으로 사용하는 비관적 락
     * 조회(리뷰 지적 — 재설정·로그인 직렬화). {@code findByEmailForUpdate()}와 같은 행에
     * {@code SELECT ... FOR UPDATE}를 걸어 여러 흐름을 같은 락으로 직렬화한다 — 잠금 없이
     * 재설정하면, 재설정 트랜잭션이 비밀번호를 아직 커밋하지 않은 사이 공격자가 옛 비밀번호로
     * 로그인을 통과해 새 Refresh Token을 저장할 수 있고, 그 직후 재설정이 커밋돼 비밀번호는
     * 바뀌어도 그 Refresh Token은 이미 저장된 뒤라 삭제되지 않는다(이번 보안 수정의 목적이
     * 무력화됨). 같은 행 락을 공유하면 어느 쪽이 먼저 락을 잡았든 나머지 흐름은 앞선 트랜잭션이
     * 커밋해 락을 놓을 때까지 대기한다 — 재설정이 먼저면 뒤이은 로그인은 이미 바뀐 비밀번호로
     * 검증되어 실패하고, 로그인이 먼저면 재설정은 로그인이 끝난 뒤 커밋되면서 로그인이 방금
     * 저장한 Refresh Token까지 함께 삭제한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(Long id);
}
