package com.doctorpet.domain.notification.service;

// 인증 principal을 알림 수신자(NotificationRecipient)로 해석한다(고도화 3.10). 회원(GUARDIAN)은 (MEMBER, memberId),
// 병원 스태프(HOSPITAL_STAFF)는 (HOSPITAL, hospitalId)로 매핑한다. hospitalId는 요청값을 신뢰하지 않고 기존 수단인
// MemberService.getMyInfo(memberId)로 서버에서 해석한다(HospitalReservationApplicationService.requireHospitalId과 동일 근거).

import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.notification.exception.NotificationErrorCode;
import com.doctorpet.global.exception.ServiceException;
import com.doctorpet.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationRecipientResolver {

    private final MemberService memberService;

    public NotificationRecipient resolve(MemberPrincipal principal) {
        // role은 서버가 서명한 JWT에서 온 값이라 신뢰할 수 있다. 스태프일 때만 소속 병원을 서버에서 재확인한다.
        if (!MemberRole.HOSPITAL_STAFF.name().equals(principal.role())) {
            return NotificationRecipient.member(principal.memberId());
        }

        MemberResponse member = memberService.getMyInfo(principal.memberId());
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            throw new ServiceException(NotificationErrorCode.NOTIFICATION_RECIPIENT_UNRESOLVED);
        }
        return NotificationRecipient.hospital(member.hospitalId());
    }
}
