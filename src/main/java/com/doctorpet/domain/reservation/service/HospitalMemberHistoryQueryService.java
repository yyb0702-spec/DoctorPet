// 병원 스태프의 회원 진료·결제 이력 조회 서비스(SA §8-6).
// 예약으로 회원을 해석하고, 그 예약이 인증 스태프의 자병원 것일 때만 회원 이력을 자병원 범위로 돌려준다
// (타 병원 방문 이력은 노출하지 않는다). 병원·회원은 요청 값이 아니라 인증 principal·예약에서 해석한다.
package com.doctorpet.domain.reservation.service;

import com.doctorpet.domain.hospital.exception.HospitalErrorCode;
import com.doctorpet.domain.member.dto.response.MemberResponse;
import com.doctorpet.domain.member.entity.MemberRole;
import com.doctorpet.domain.member.service.MemberService;
import com.doctorpet.domain.reservation.dto.response.HospitalMemberHistoryItemResponse;
import com.doctorpet.domain.reservation.entity.Reservation;
import com.doctorpet.domain.reservation.exception.ReservationErrorCode;
import com.doctorpet.domain.reservation.repository.HospitalMemberHistoryQueryRepository;
import com.doctorpet.domain.reservation.repository.ReservationRepository;
import com.doctorpet.global.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalMemberHistoryQueryService {

    private final MemberService memberService;
    private final ReservationRepository reservationRepository;
    private final HospitalMemberHistoryQueryRepository hospitalMemberHistoryQueryRepository;

    public Page<HospitalMemberHistoryItemResponse> getMemberHistory(
            Long staffMemberId,
            Long reservationId,
            Pageable pageable
    ) {
        Long hospitalId = requireHospitalId(staffMemberId);
        // 자병원 예약이 아니면 존재를 드러내지 않고 404로 끝낸다(타 병원 회원 이력 열람 차단).
        Reservation reservation = reservationRepository
                .findByIdAndHospitalId(reservationId, hospitalId)
                .orElseThrow(() -> new ServiceException(ReservationErrorCode.RESERVATION_NOT_FOUND));

        return hospitalMemberHistoryQueryRepository.findMemberHistory(
                hospitalId,
                reservation.getMemberId(),
                pageable
        );
    }

    private Long requireHospitalId(Long staffMemberId) {
        MemberResponse member = memberService.getMyInfo(staffMemberId);
        if (member.role() != MemberRole.HOSPITAL_STAFF || member.hospitalId() == null) {
            throw new ServiceException(HospitalErrorCode.NOT_OWN_HOSPITAL);
        }
        return member.hospitalId();
    }
}
