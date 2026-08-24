// 병원 스태프가 보는 회원 진료·결제 이력 한 행 (SA §8-6). 자병원에서의 과거 예약 + 활성 결제.
import type { PaymentStatus, ReservationStatus } from '@/types/enums'

export interface HospitalMemberHistoryItem {
  reservationId: number
  reservedAt: string
  petName: string
  petSpecies: string
  reservationStatus: ReservationStatus
  // 결제 없는 예약은 null. 영수증은 PAID·OFFLINE_PAID·REFUNDED일 때만 제공된다.
  paymentId: number | null
  paymentStatus: PaymentStatus | null
  amount: number | null
}
