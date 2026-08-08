package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentHistoryResponse;
import com.doctorpet.domain.payment.entity.Payment;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  결제 내역 조회(#47, SA §8-7). 보호자(본인 예약)·병원 스태프(자병원 예약)가 권한 범위 내에서만 조회한다.
  예약 소유권·병원 정보는 예약 Repository를 직접 호출하지 않고 port(ReservationLookupPort)로만 얻는다(가드레일).
  예약당 결제는 1건(UNIQUE)이라 0..1건이며, 향후 결제 이력 1:N 확장을 감안해 리스트로 반환한다.
  결제 전이면 빈 리스트를 준다(예약은 존재하지만 아직 청구가 없는 경우).
 */
@Service
@RequiredArgsConstructor
public class PaymentQueryService {

    private final ReservationLookupPort reservationLookupPort;
    private final StaffHospitalPort staffHospitalPort;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public Optional<PaymentStatus> findStatusByReservationId(Long reservationId) {
        return paymentRepository.findByReservationId(reservationId)
                .map(Payment::getStatus);
    }

    /** 보호자 본인 예약의 결제 내역. 본인 예약이 아니면 403. */
    @Transactional(readOnly = true)
    public List<PaymentHistoryResponse> getForGuardian(Long reservationId, Long memberId) {
        ReservationChargeView reservation = loadReservation(reservationId);
        if (!reservation.guardianMemberId().equals(memberId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }
        return toHistory(reservationId);
    }

    /** 병원 스태프 자병원 예약의 결제 내역. 소속 병원이 없거나 타병원 예약이면 403. */
    @Transactional(readOnly = true)
    public List<PaymentHistoryResponse> getForHospital(Long reservationId, Long staffMemberId) {
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.FORBIDDEN));
        ReservationChargeView reservation = loadReservation(reservationId);
        if (!reservation.hospitalId().equals(staffHospitalId)) {
            throw new ServiceException(CommonErrorCode.FORBIDDEN);
        }
        return toHistory(reservationId);
    }

    private ReservationChargeView loadReservation(Long reservationId) {
        return reservationLookupPort.findForCharge(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));
    }

    private List<PaymentHistoryResponse> toHistory(Long reservationId) {
        return paymentRepository.findByReservationId(reservationId)
                .map(PaymentHistoryResponse::from)
                .map(List::of)
                .orElseGet(List::of);
    }
}
