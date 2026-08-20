// 예약 결제수단 재지정이 가능한지 판정한다(SA §8-7, PR #152).
// 최종 판정은 서버가 예약 행을 PESSIMISTIC_WRITE로 잠근 뒤 하므로 이 함수는 화면 노출 조건일 뿐이다 —
// 여기서 통과해도 청구가 먼저 커밋되면 서버가 RESERVATION_019로 거절한다.
import { ReservationStatus } from '@/types/enums'
import type { PaymentRecord } from '@/features/payments/types'

// 진료 시작 전 상태만 허용한다. 서버도 REQUESTED·CONFIRMED에서만 받고, 그 뒤 상태는
// RESERVATION_018(PAYMENT_METHOD_CHANGE_NOT_ALLOWED)로 거절한다.
const CHANGEABLE: ReservationStatus[] = [
  ReservationStatus.REQUESTED,
  ReservationStatus.CONFIRMED,
]

/**
 * 결제 선기록이 하나라도 있으면 상태와 무관하게 불가다(서버: RESERVATION_019). 결제 내역을 아직
 * 못 받았으면(undefined) 판정을 보류하고 false를 준다 — 있는데 없다고 보고 화면을 열면
 * 눌러도 거절되는 버튼을 보여주게 된다.
 */
export function canChangePaymentMethod(
  reservationStatus: ReservationStatus,
  payments: PaymentRecord[] | undefined,
): boolean {
  if (payments == null) return false
  return CHANGEABLE.includes(reservationStatus) && payments.length === 0
}
