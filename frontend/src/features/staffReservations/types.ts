// 병원 스태프 예약 운영 도메인 타입 (SA §8-6, /api/hospital/reservations/**).
import type { ReservationStatus } from '@/types/enums'

export interface ReservationHistory {
  totalReservationCount: number
  completedCount: number
  cancelCount: number
  noShowCount: number
}

// HospitalReservationListItemResponse. 증상/메모 필드는 백엔드에 없다.
export interface StaffReservationListItem {
  reservationId: number
  memberId: number
  petId: number
  petName: string
  reservedAt: string
  reservationStatus: ReservationStatus
  rejectionReason: string | null
  reservationHistory: ReservationHistory
}

export interface StaffReservationListParams {
  status?: ReservationStatus
  page?: number // 0-base
  size?: number
}

// 예약 거절 사유 — 백엔드 ReservationRejectReason 화이트리스트, 자유 텍스트 아님.
export const REJECT_REASONS = [
  '직원 부족',
  '슬롯 등록 오류',
  '진료 불가',
  '기타',
] as const
