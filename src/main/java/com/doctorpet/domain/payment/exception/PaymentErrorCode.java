package com.doctorpet.domain.payment.exception;

import com.doctorpet.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
  진료비 결제 도메인 에러코드(PAYMENT_NNN). 결제수단(PaymentMethodErrorCode, PAYMENT_METHOD_NNN)과
  번호·의미가 겹치지 않도록 도메인별 enum으로 분리한다(AGENTS·코드컨벤션).
  게이트웨이 승인 실패(잔액·한도·만료·장애)는 HTTP 에러가 아니라 OFFLINE_REQUIRED 상태로 귀결되므로(SA §9-4)
  별도 에러코드가 아니라 정상 201 응답의 status로 표현한다 — 여기 코드는 청구 자체를 거부하는 경우다.
 */
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    // 청구 금액이 0 이하이거나 절대 상한(설정값)을 초과.
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "PAYMENT_001", "청구 금액이 올바르지 않습니다."),
    // 진료 완료 상태가 아닌 예약에 청구를 시도.
    RESERVATION_NOT_CHARGEABLE(HttpStatus.CONFLICT, "PAYMENT_002", "진료가 완료된 예약만 청구할 수 있습니다."),
    // 스태프 소속 병원과 예약의 병원이 다르거나, 병원 소속이 없는 계정. 존재 여부 노출을 막기 위해 403으로 통일.
    FORBIDDEN_HOSPITAL(HttpStatus.FORBIDDEN, "PAYMENT_003", "해당 예약을 청구할 권한이 없습니다."),
    // 예약당 이미 결제 레코드가 존재(이중 청구). reservation_id UNIQUE의 사전 체크·경쟁 상태 방어.
    DUPLICATE_CHARGE(HttpStatus.CONFLICT, "PAYMENT_004", "이미 청구된 예약입니다."),
    // 결제 레코드를 찾을 수 없음(후확정 단계 방어선).
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_005", "결제 정보를 찾을 수 없습니다."),
    // 오프라인 정산은 OFFLINE_REQUIRED 상태에서만 가능. PENDING·PAID 등 허용되지 않은 상태에서 시도한 경우(#36).
    OFFLINE_PRECONDITION_FAILED(HttpStatus.CONFLICT, "PAYMENT_006", "오프라인 정산이 가능한 상태가 아닙니다."),
    // 웹훅 서명 검증 실패(#48). 위조·재전송 등 신뢰할 수 없는 요청을 401로 거부한다.
    // PAYMENT_006은 develop 병합분(#36 OFFLINE_PRECONDITION_FAILED)이 선점해 007로 재번호했다.
    WEBHOOK_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "PAYMENT_007", "결제 웹훅 서명 검증에 실패했습니다."),
    // 환불은 빌링키 결제 완료(PAID·BILLING_KEY)에서만 가능. PENDING·OFFLINE_REQUIRED·OFFLINE_PAID에서 시도한 경우(#37).
    // 현장 현금 수납(OFFLINE_PAID)의 환불은 반환 절차·증빙 정책이 미정이라 범위 밖이며 이 코드로 거부한다.
    REFUND_PRECONDITION_FAILED(HttpStatus.CONFLICT, "PAYMENT_008", "환불이 가능한 상태가 아닙니다."),
    // 같은 결제에 대한 환불이 이미 진행 중(신선한 REQUESTED 선점). 동시 환불 요청 중 선점에서 진 요청이 받는다.
    // 재요청하면 앞선 요청의 결과(환불 완료 또는 실패)에 따라 멱등 응답 또는 재시도로 갈린다.
    REFUND_IN_PROGRESS(HttpStatus.CONFLICT, "PAYMENT_009", "환불 처리가 이미 진행 중입니다. 잠시 후 다시 확인해 주세요."),
    // PG 취소 호출이 실패해 환불이 성립하지 않음. 결제는 PAID로 남고 같은 멱등키로 재시도할 수 있다.
    // 게이트웨이 오류 원문·공급자 코드는 응답에 담지 않는다(내부 구현 노출 금지).
    REFUND_GATEWAY_FAILED(HttpStatus.BAD_GATEWAY, "PAYMENT_010", "환불 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    // PG 취소는 성립했는데 결제를 REFUNDED로 확정하지 못한 경우(조건부 UPDATE 0건, PR #112 리뷰 P1).
    // 이력만 COMPLETED로 커밋하면 결제와 영구히 어긋나고 재요청도 그 이력에 막혀 복구되지 않으므로,
    // 이 코드로 예외를 던져 이력 변경까지 함께 롤백한다. 롤백 후 REQUESTED 선점이 남아, 임계 경과 후
    // 같은 멱등키 재시도가 PG의 기존 취소 결과를 받아 자가 복구한다.
    REFUND_STATE_CONFLICT(HttpStatus.CONFLICT, "PAYMENT_011", "환불 상태를 확정할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    // 청구 항목 자체가 성립하지 않는 경우(빈 목록·항목명 누락·수량 0 이하). 금액 범위 문제는 INVALID_AMOUNT로 구분한다
    // — 항목 구조가 틀린 것과 합계가 허용 범위를 벗어난 것은 스태프가 고쳐야 할 지점이 다르다(SA §9-4 청구 항목).
    INVALID_PAYMENT_ITEM(HttpStatus.BAD_REQUEST, "PAYMENT_012", "청구 항목이 올바르지 않습니다."),
    // 영수증은 결제가 확정된 건(PAID·OFFLINE_PAID·REFUNDED)만 발급한다. PENDING·OFFLINE_REQUIRED는 아직
    // 수납이 끝나지 않아 증빙 대상이 아니다(SA §9-4 영수증).
    RECEIPT_NOT_AVAILABLE(HttpStatus.CONFLICT, "PAYMENT_013", "영수증을 발급할 수 있는 결제가 아닙니다."),
    // 이미 청구가 시작된(선기록이 존재하는) 예약의 항목을 수정·삭제하려 한 경우(SA §9-4 청구 항목).
    // 항목은 청구 시점 스냅샷이고, 이 금지는 정정 재청구를 우회 구현하지 못하게 하는 경계다 —
    // 기존 결제의 금액 정정은 항목 수정이 아니라 정정 재청구 절차만 쓴다.
    PAYMENT_ITEM_ALREADY_CHARGED(HttpStatus.CONFLICT, "PAYMENT_014", "이미 청구된 예약의 진료 항목은 수정할 수 없습니다."),
    // 초안 항목이 없는 예약에 청구를 시도한 경우(SA §9-4 — 초안 항목이 0건이면 청구를 거부한다).
    // 요청이 잘못된 것이 아니라 청구 전제(항목 작성)가 아직 성립하지 않은 상태라 400이 아니라 409다.
    PAYMENT_ITEM_REQUIRED(HttpStatus.CONFLICT, "PAYMENT_015", "청구할 진료 항목이 없습니다. 항목을 먼저 등록해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
