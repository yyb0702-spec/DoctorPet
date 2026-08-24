// 병원 스태프 결제 도메인 타입 (SA §8-7, /api/hospital/**).
import type { PaymentStatus } from '@/types/enums'
import type { PaymentChargeResult } from '@/features/payments/types'

// 병원 결제/환불 대시보드 목록 항목(GET /api/hospital/payments, SA §8-7 v1.64).
// 자병원 예약의 '활성 결제(superseded_at IS NULL)'만 반환하므로 결제가 붙은 행만 온다 —
// paymentId·paymentStatus·amount는 항상 채워지고, paidAt·offlineSettledAt·refundedAt·failedAt만
// 결제 상태에 따라 null이다. 식별자 필드를 nullable로 둔 것은 응답 방어용이며 '청구 전' 행 계약이 아니다.
export interface HospitalPaymentListItem {
  reservationId: number
  memberId: number
  petId: number
  petName: string
  reservedAt: string
  paymentId: number | null
  paymentStatus: PaymentStatus | null
  amount: number | null
  paidAt: string | null
  offlineSettledAt: string | null
  refundedAt: string | null
  failedAt: string | null
}

// PaymentChargeResponse — 진료비 청구 결과. PENDING이면 정산 스케줄러가 나중에
// 확정하므로 사람이 강제로 바꾸는 API는 없다(§9-4 전진 단선 상태 머신).
// 보호자 셀프 복구와 같은 응답(PaymentChargeResponse)이라 정의를 공유한다 — 같은 계약을 두 벌
// 두면 백엔드 DTO가 바뀔 때 한쪽만 고쳐질 수 있다.
export type StaffChargeResult = PaymentChargeResult

// 청구 항목 초안(GET/PUT /api/hospital/reservations/{id}/payment-items, SA §8-7·§9-4).
// 항목 금액(amount)과 총액은 서버가 산출하므로 입력에는 없고 응답에만 있다.
export interface PaymentItemInput {
  name: string
  quantity: number
  unitPrice: number
}

export interface PaymentItemDraft extends PaymentItemInput {
  amount: number
}

// 초안 조회·저장 응답. draftToken은 낙관적 검증용 서버 산출 값으로, 청구 요청에 그대로 실어 보낸다.
// 저장과 청구 사이에 다른 직원이 초안을 바꾸면 서버가 이 토큰 불일치로 409를 준다(SA §9-4).
export interface PaymentItemDraftResult {
  items: PaymentItemDraft[]
  draftToken: string
}

// 병원 스태프 결제 내역 조회(GET .../{id}/payments)는 보호자용과 같은
// PaymentHistoryResponse[]다 — features/payments의 PaymentRecord를 그대로 쓴다.
export type { PaymentRecord } from '@/features/payments/types'
