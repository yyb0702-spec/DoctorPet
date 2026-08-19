// 예약 대기열 도메인 타입 (백엔드 ReservationWaitlist* 계약 기준).
// 순번(position)은 응답에 없다 — "몇 번째 대기" 표시는 백엔드 변경 없이는 불가.
import type { ReservationResponse } from '@/features/reservations/types'

// 대기열 상태 (백엔드 ReservationWaitlistStatus, 6값).
// WAITING → (슬롯 비면) OFFERED → ACCEPTED / REJECTED / EXPIRED, 또는 WAITING → CANCELED.
export const WaitlistStatus = {
  WAITING: 'WAITING',
  OFFERED: 'OFFERED',
  ACCEPTED: 'ACCEPTED',
  REJECTED: 'REJECTED',
  EXPIRED: 'EXPIRED',
  CANCELED: 'CANCELED',
} as const
export type WaitlistStatus = (typeof WaitlistStatus)[keyof typeof WaitlistStatus]

// ReservationWaitlistResponse. requestedAt은 백엔드 createdAt 스냅샷.
// offeredAt·offerExpiresAt은 OFFERED 상태에서만 채워지고, respondedAt/canceledAt은 종료 시점.
export interface Waitlist {
  waitlistId: number
  slotId: number
  status: WaitlistStatus
  requestedAt: string
  offeredAt: string | null
  offerExpiresAt: string | null
  respondedAt: string | null
  canceledAt: string | null
}

// POST /api/reservation-waitlists/{id}/accept 요청. 승급 제안 수락 → 예약 생성.
export interface WaitlistAcceptInput {
  petId: number
  paymentMethodId: number
}

// accept 성공 응답은 예약 요청 응답과 동일한 계약이다.
export type WaitlistAcceptResult = ReservationResponse
