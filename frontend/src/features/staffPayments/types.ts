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

// 병원 스태프 결제 내역 조회(GET .../{id}/payments)는 보호자용과 같은
// PaymentHistoryResponse[]다 — features/payments의 PaymentRecord를 그대로 쓴다.
export type { PaymentRecord } from '@/features/payments/types'
