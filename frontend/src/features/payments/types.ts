// 결제 도메인 타입.
import type { PaymentChannel, PaymentStatus } from '@/types/enums'

// PaymentMethodResponse (실연동). status는 백엔드가 String(enum name)으로 반환.
export interface PaymentMethod {
  id: number
  cardBrand: string | null
  cardLast4: string | null
  status: string // ACTIVE / EXPIRED / DELETED
  createdAt: string
}

// 결제 내역 항목 (실연동, GET /reservations/{id}/payments → PaymentHistoryResponse[], #47).
// 예약당 여러 건일 수 있다(예: 빌링키 자동 청구 실패 → 오프라인 수납).
// 민감정보(빌링키·카드번호 원본)는 없고 표시용 스냅샷(brand·last4)만 온다.
export interface PaymentRecord {
  paymentId: number
  reservationId: number
  status: PaymentStatus
  paymentChannel: PaymentChannel | null // BILLING_KEY / OFFLINE
  amount: number
  cardBrandSnapshot: string | null
  cardLast4Snapshot: string | null
  createdAt: string
  paidAt: string | null // 빌링키 결제 완료 시각
  failedAt: string | null // 자동 청구 실패로 오프라인 전환된 시각(offline_required_at)
  offlineSettledAt: string | null // 병원 현장 수납 완료 시각(#36)
  refundedAt: string | null // 전액 환불 확정 시각(MVP+ #37)
}
