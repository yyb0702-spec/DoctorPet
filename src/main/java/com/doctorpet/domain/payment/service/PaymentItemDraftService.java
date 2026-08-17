package com.doctorpet.domain.payment.service;

import com.doctorpet.domain.payment.dto.response.PaymentItemDraftResponse;
import com.doctorpet.domain.payment.entity.PaymentItem;
import com.doctorpet.domain.payment.entity.PaymentStatus;
import com.doctorpet.domain.payment.exception.PaymentErrorCode;
import com.doctorpet.domain.payment.port.ReservationChargeView;
import com.doctorpet.domain.payment.port.ReservationLookupPort;
import com.doctorpet.domain.payment.port.StaffHospitalPort;
import com.doctorpet.domain.payment.repository.PaymentItemRepository;
import com.doctorpet.domain.payment.repository.PaymentRepository;
import com.doctorpet.global.exception.CommonErrorCode;
import com.doctorpet.global.exception.ServiceException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
  청구 항목 초안 작성·조회(SA §9-4 "청구 항목", SA §4 payment_items).

  작성·수정 창은 **진료 완료 후부터 청구 선기록 전까지**이고, 주체는 자병원 병원 스태프뿐이다. 선기록 이후에는
  항목을 절대 수정·삭제하지 않는다 — 이 금지는 스냅샷 정합성뿐 아니라 정정 재청구를 우회 구현하지 못하게 하는
  경계다(기존 결제의 금액 정정은 항목 수정이 아니라 정정 재청구 절차만 쓴다).

  직렬화(STRICT) — 이게 없으면 위 금지가 경합에서 뚫린다:
  - 항목 쓰기와 청구 선기록은 **같은 예약 행을 PESSIMISTIC_WRITE로 잠근다**(findForChargeForUpdate — 청구가 이미
    쓰는 락이고 결제수단 재지정도 같은 락을 공유한다). 락 없이 "아직 결제가 없다"를 확인하고 쓰면 check-then-act라,
    청구가 기존 항목으로 총액을 선기록한 직후 항목 수정이 커밋되어 영수증 항목 합계와 payments.amount가 갈라진다.
  - 쓰기 자체도 `WHERE payment_id IS NULL` 조건부다(2차 방어선). 스탬프된 항목은 조건이 성립하지 않아 지워지지 않는다.

  "이미 청구됨" 판정은 결제 존재 여부(existsByReservationId)로 한다. 조건부 삭제의 0건으로 판정하지 않는 이유는,
  초안을 처음 작성하는 정상 요청도 삭제 0건이라 "청구됨"과 구분되지 않기 때문이다. 락 아래에서 읽으므로 이 검사는
  check-then-act가 아니다.
 */
@Service
@RequiredArgsConstructor
public class PaymentItemDraftService {

    private final PaymentItemRepository paymentItemRepository;
    private final PaymentRepository paymentRepository;
    private final ReservationLookupPort reservationLookupPort;
    private final StaffHospitalPort staffHospitalPort;
    private final PaymentAmountPolicy amountPolicy;

    /**
     * 초안 항목 전체 교체. 부분 수정이 아니라 스태프가 확정한 최종 목록으로 바꾼다 —
     * 삭제·수정·추가가 한 번의 조건부 쓰기로 처리돼 청구와의 경합 지점이 하나로 모인다.
     */
    @Transactional
    public PaymentItemDraftResponse replaceDrafts(
            Long reservationId, Long staffMemberId, List<PaymentItemCommand> items
    ) {
        // 예약 행 락을 먼저 잡아 청구 선기록과 직렬화한다. 이후 검사·쓰기는 모두 이 락 아래에서 일어난다.
        ReservationChargeView reservation = loadChargeableReservationForUpdate(reservationId, staffMemberId);

        // 청구가 시작된 뒤에는 항목을 바꿀 수 없다. 락 아래 조회이므로 이 직후 청구가 끼어들 수 없다.
        //
        // 단순히 "활성 결제가 있으면 거부"로 하면 정정 재청구(3.5-a)도 막힌다 — 전액 환불은 superseded_at을 세우지
        // 않아 REFUNDED 결제가 그대로 활성이기 때문이다. 정정 재청구는 그 REFUNDED를 대체하기 전에 새 초안을
        // 작성해야 하므로, 활성 결제가 **REFUNDED일 때만** 초안 작성을 허용하고 PENDING·PAID·OFFLINE_PAID·
        // OFFLINE_REQUIRED는 거부한다(OFFLINE_REQUIRED는 셀프 복구가 원 항목을 복제하므로 초안이 필요 없다).
        // 대체된 과거 결제(superseded_at 설정)는 활성이 아니라 검사 대상이 아니다(고도화 3.5-a "초안 게이트의 술어").
        paymentRepository.findActiveByReservationId(reservation.reservationId())
                .filter(active -> active.getStatus() != PaymentStatus.REFUNDED)
                .ifPresent(active -> {
                    throw new ServiceException(PaymentErrorCode.PAYMENT_ITEM_ALREADY_CHARGED);
                });

        // 저장 전에 합계까지 검증해, 어차피 청구할 수 없는 구성이 초안으로 남지 않게 한다(청구가 최종 게이트).
        amountPolicy.totalOfCommands(items);

        paymentItemRepository.deleteDraftsByReservationId(reservationId);
        List<PaymentItem> saved = paymentItemRepository.saveAll(items.stream()
                .map(item -> PaymentItem.draft(
                        reservationId, item.name().trim(), item.quantity(), item.unitPrice(),
                        amountPolicy.lineAmount(item)))
                .toList());
        return PaymentItemDraftResponse.from(saved);
    }

    /** 초안 항목 조회. 청구 전 스태프가 작성 중인 목록을 확인한다. 청구 후에는 스탬프돼 빈 목록이 된다. */
    @Transactional(readOnly = true)
    public PaymentItemDraftResponse getDrafts(Long reservationId, Long staffMemberId) {
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL));
        ReservationChargeView reservation = reservationLookupPort.findForCharge(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));
        if (!staffHospitalId.equals(reservation.hospitalId())) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }
        return PaymentItemDraftResponse.from(
                paymentItemRepository.findByReservationIdAndPaymentIdIsNullOrderByIdAsc(reservationId));
    }

    /**
     * 자병원 권한·진료 완료 전제를 확인하고 예약 행을 잠근다. 검증 순서와 에러코드는 청구 선기록
     * (PaymentChargeService.preRecord)과 같게 유지한다 — 같은 창·같은 전제를 보는 두 경로가 다른 답을 주면
     * 스태프가 "항목은 저장되는데 청구가 안 되는" 상태를 만난다.
     */
    private ReservationChargeView loadChargeableReservationForUpdate(Long reservationId, Long staffMemberId) {
        Long staffHospitalId = staffHospitalPort.findHospitalIdByMemberId(staffMemberId)
                .orElseThrow(() -> new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL));
        ReservationChargeView reservation = reservationLookupPort.findForChargeForUpdate(reservationId)
                .orElseThrow(() -> new ServiceException(CommonErrorCode.NOT_FOUND));
        if (!staffHospitalId.equals(reservation.hospitalId())) {
            throw new ServiceException(PaymentErrorCode.FORBIDDEN_HOSPITAL);
        }
        if (!reservation.treatmentCompleted()) {
            throw new ServiceException(PaymentErrorCode.RESERVATION_NOT_CHARGEABLE);
        }
        return reservation;
    }
}
