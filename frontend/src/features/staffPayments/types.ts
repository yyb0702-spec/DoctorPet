// 병원 스태프 결제 도메인 타입 (SA §8-7, /api/hospital/**).
import type { PaymentStatus } from '@/types/enums'

// 병원 결제/환불 대시보드 목록 항목(GET /api/hospital/payments). 진료 완료 예약 기준이며,
// 아직 청구 전이면 paymentId 이하 필드가 전부 null이다.
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
export interface StaffChargeResult {
  paymentId: number
  reservationId: number
  status: PaymentStatus
  amount: number
  cardBrandSnapshot: string | null
  cardLast4Snapshot: string | null
  failureReason: string | null
}

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
