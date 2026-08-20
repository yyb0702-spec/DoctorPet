// 예약 결제수단 재지정 노출 조건. 서버 거절 조건(RESERVATION_018·019)과 어긋나면
// "눌러도 거절되는 버튼"이 되므로 상태 전수와 결제 유무 조합을 고정한다.
import { describe, expect, it } from 'vitest'
import { canChangePaymentMethod } from './paymentMethodChange'
import { ReservationStatus } from '@/types/enums'
import type { PaymentRecord } from '@/features/payments/types'

const PAYMENT = { paymentId: 1 } as PaymentRecord

describe('canChangePaymentMethod', () => {
  it('진료 시작 전(REQUESTED·CONFIRMED)이고 결제 선기록이 없으면 허용한다', () => {
    expect(canChangePaymentMethod(ReservationStatus.REQUESTED, [])).toBe(true)
    expect(canChangePaymentMethod(ReservationStatus.CONFIRMED, [])).toBe(true)
  })

  it('결제 선기록이 있으면 상태와 무관하게 막는다(서버 RESERVATION_019)', () => {
    expect(canChangePaymentMethod(ReservationStatus.REQUESTED, [PAYMENT])).toBe(
      false,
    )
    expect(canChangePaymentMethod(ReservationStatus.CONFIRMED, [PAYMENT])).toBe(
      false,
    )
  })

  it('진료가 시작된 뒤·종료 상태에서는 막는다(서버 RESERVATION_018)', () => {
    const blocked = [
      ReservationStatus.CHECKED_IN,
      ReservationStatus.IN_TREATMENT,
      ReservationStatus.TREATMENT_COMPLETED,
      ReservationStatus.CANCELED,
      ReservationStatus.REJECTED,
      ReservationStatus.NO_SHOW_PENDING,
      ReservationStatus.NO_SHOW,
    ]
    for (const status of blocked) {
      expect(canChangePaymentMethod(status, [])).toBe(false)
    }
  })

  it('결제 내역을 아직 못 받았으면 판정을 보류한다', () => {
    expect(canChangePaymentMethod(ReservationStatus.CONFIRMED, undefined)).toBe(
      false,
    )
  })
})
