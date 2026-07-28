package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    /*
     * 내 정보 조회. SA §8-1.
     * Access Token 자체는 유효해도(만료 전) 그 사이 탈퇴 등으로 회원이 존재하지 않을 수 있는
     * 좁은 race condition을 대비해 MEMBER_NOT_FOUND(404)로 방어한다.
     */
    @Transactional(readOnly = true)
    public MemberResponse getMyInfo(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        return MemberResponse.from(member);
    }
}
