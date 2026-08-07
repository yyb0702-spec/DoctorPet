package com.doctorpet.domain.member.service;

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.Member;
import com.doctorpet.domain.member.exception.MemberErrorCode;
import com.doctorpet.domain.member.repository.MemberRepository;
import com.doctorpet.global.exception.ServiceException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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

    /*
     * 다른 도메인이 memberId 기반으로 새 리소스를 쓰기(등록)하기 전에 활성 회원인지 확인할 때
     * 쓴다(구현 가드레일 — 다른 도메인은 Member의 Repository를 직접 참조하지 않고 이 Service를
     * 경유한다). Access Token 자체는 유효해도(만료 전) 그 사이 탈퇴 등으로 회원이 존재하지 않을
     * 수 있는 좁은 race condition을 대비한다 — 이 방어가 없으면 이미 탈퇴한 memberId로 다른
     * 도메인에 고아 행이 생성될 수 있다. Member의 @SQLRestriction("deleted_at is null") 덕분에
     * existsById도 활성 회원만 대상으로 확인한다.
     */
    @Transactional(readOnly = true)
    public void assertActiveMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND);
        }
    }

    /*
     * 프로필 수정(닉네임만). SA엔 아직 없는 API였으나 A 도메인 MVP 고도화 항목으로 추가한다.
     * email·password는 대상이 아니다 — Member.updateNickname() 참고.
     */
    @Transactional
    public MemberResponse updateNickname(Long memberId, String nickname) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        member.updateNickname(nickname);

        return MemberResponse.from(member);
    }

    /*
     * 병원이 예약 목록을 조회할 때 보호자 연락처를 함께 보여주기 위한 배치 조회(기능 구멍 점검
     * 대응 — HospitalReservationListItemResponse). 다른 도메인은 Member의 Repository를 직접
     * 참조하지 않고 이 Service를 경유한다(구현 가드레일). 존재하지 않거나 탈퇴한 회원은 결과
     * Map에 키 자체가 없다 — 호출부(HospitalReservationApplicationService)는 Map.get()으로
     * 조회해 키가 없든(존재하지 않는 회원) 값이 null이든(phone 미입력 기존 회원) 둘 다 자연스럽게
     * null로 받는다.
     */
    @Transactional(readOnly = true)
    public Map<Long, String> getPhonesByMemberIds(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        // Collectors.toMap은 값이 null이면 내부적으로 Map.merge를 타면서 NullPointerException을
        // 던진다 — phone은 nullable(기존 회원)이라 흔히 null일 수 있으므로 직접 HashMap에 채운다.
        Map<Long, String> phonesByMemberId = new HashMap<>();
        memberRepository.findAllById(memberIds)
                .forEach(member -> phonesByMemberId.put(member.getId(), member.getPhone()));
        return phonesByMemberId;
    }

    /*
     * 실제 탈퇴 처리(Soft Delete + 이메일 익명화). 활성 예약·미수금 보유 여부 확인은 이
     * 메서드의 책임이 아니다 — MemberWithdrawalApplicationService가 다른 도메인 Service를
     * 통해 먼저 확인하고 통과한 경우에만 이 메서드를 호출해야 한다(구현 가드레일).
     */
    @Transactional
    public void withdraw(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ServiceException(MemberErrorCode.MEMBER_NOT_FOUND));

        member.withdraw(LocalDateTime.now());
    }
}
