package com.doctorpet.domain.member.repository;

import com.doctorpet.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * Member 엔티티의 {@code @SQLRestriction("deleted_at is null")} 덕분에
     * 이 메서드는 자동으로 활성 회원만 대상으로 중복 여부를 확인한다(SA §6-3).
     */
    boolean existsByEmail(String email);
}
