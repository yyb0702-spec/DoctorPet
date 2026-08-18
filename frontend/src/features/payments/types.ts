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

// 환불 이력 상태(SA §4 payment_refunds). 결제 상태(PaymentStatus)와 별개로 환불 처리 진행을 나타낸다.
export type RefundStatus = 'REQUESTED' | 'COMPLETED' | 'FAILED'

// 영수증 항목 1건(PaymentItemResponse). 할인·조정은 음수 unitPrice·amount로 표시된다.
// 항목화 이전 결제는 항목이 없어 items가 빈 배열이다(백필하지 않음, SA §9-4).
export interface ReceiptItem {
  name: string
  quantity: number
  unitPrice: number
  amount: number
}

// JSON 영수증(GET /api/payments/{id}/receipt · /api/hospital/payments/{id}/receipt, PR #158).
// PAID·OFFLINE_PAID·REFUNDED 결제에만 제공된다. 결제 시점 스냅샷(항목·총액·카드 brand/last4,
// 예약 시점 펫 이름·종)만 담고, 환불 사유·빌링키·카드번호 원본은 담지 않는다(SA §9-4).
export interface Receipt {
  paymentId: number
  reservationId: number
  hospitalId: number
  guardianMemberId: number
  petId: number
  petName: string
  petSpecies: string // 백엔드 PetSpecies 8종 중 하나(DOG/CAT/BIRD/RABBIT/HAMSTER/GUINEA_PIG/FERRET/REPTILE), 예약 시점 스냅샷
  status: PaymentStatus
  paymentChannel: PaymentChannel | null // BILLING_KEY / OFFLINE
  paidAt: string | null // 빌링키 자동 결제 완료 시각
  offlineSettledAt: string | null // 현장 수납 완료 시각
  cardBrandSnapshot: string | null
  cardLast4Snapshot: string | null
  items: ReceiptItem[]
  totalAmount: number // payments.amount 정본(항목 합계 재계산 아님)
  refundStatus: RefundStatus | null // 환불 이력이 없으면 null
  refundedAt: string | null
}
