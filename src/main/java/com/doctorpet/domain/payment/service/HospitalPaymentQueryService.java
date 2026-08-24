// 병원 스태프 결제 목록 조회 서비스 — 인증 스태프의 자병원으로만 스코프한다(SA §8-7·가드레일).
package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.payment.dto.response.HospitalPaymentListItemResponse;
import com.doctorpet.domain.payment.repository.HospitalPaymentQueryRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalPaymentQueryService {

    private final MemberService memberService;
    private final HospitalPaymentQueryRepository hospitalPaymentQueryRepository;

    public Page<HospitalPaymentListItemResponse> getHospitalPayments(
            Long staffMemberId,
            Pageable pageable
    ) {
        Long hospitalId = requireHospitalId(staffMemberId);
        return hospitalPaymentQueryRepository.findActivePaymentsByHospitalId(
                hospitalId,
                pageable
        );
    }

    // 병원은 요청 값이 아니라 인증 principal로만 해석한다(스태프 소속·역할 검증).
    private Long requireHospitalId(Long staffMemberId) {
        MemberResponse member = memberService.getMyInfo(staffMemberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        return member.hospitalId();
    }
}
