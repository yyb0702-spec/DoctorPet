// 활성 결제 판정 — 1:N 결제 이력에서 최신(=활성)을 고르는지 검증(정정 재청구 게이팅의 근거, SA §9-4).
import { describe, expect, it } from 'vitest'
import { activePaymentId, rechargeablePaymentId } from './activePayment'
import type { PaymentRecord } from '@/features/payments/types'

function payment(overrides: Partial<PaymentRecord>): PaymentRecord {
  return {
    paymentId: 1,
    reservationId: 100,
    status: 'PAID',
    paymentChannel: 'BILLING_KEY',
    amount: 50000,
    cardBrandSnapshot: 'KB',
    cardLast4Snapshot: '1234',
    createdAt: '2026-08-10T10:00:00',
    paidAt: null,
    failedAt: null,
    offlineSettledAt: null,
    refundedAt: null,
    ...overrides,
  }
}

describe('activePaymentId', () => {
  it('결제가 없으면 null', () => {
    expect(activePaymentId([])).toBeNull()
  })

  it('한 건이면 그 결제', () => {
    expect(activePaymentId([payment({ paymentId: 7 })])).toBe(7)
  })

  it('여러 건이면 가장 최근 생성분(순서 무관)', () => {
    const payments = [
      payment({ paymentId: 1, createdAt: '2026-08-10T10:00:00' }),
      payment({ paymentId: 3, createdAt: '2026-08-12T09:00:00' }),
      payment({ paymentId: 2, createdAt: '2026-08-11T15:00:00' }),
    ]
    expect(activePaymentId(payments)).toBe(3)
  })

  it('정정 시나리오 — 대체된 REFUNDED(과거) + 활성 PAID(최신)면 활성은 최신 결제', () => {
    const payments = [
      payment({ paymentId: 10, status: 'REFUNDED', createdAt: '2026-08-10T10:00:00' }),
      payment({ paymentId: 11, status: 'PAID', createdAt: '2026-08-13T10:00:00' }),
    ]
    // 최신(11, PAID)이 활성 → 과거 REFUNDED(10)에는 정정 버튼이 붙지 않는다.
    expect(activePaymentId(payments)).toBe(11)
  })
})

describe('rechargeablePaymentId', () => {
  it('결제가 없으면 null', () => {
    expect(rechargeablePaymentId([])).toBeNull()
  })

  it('활성 결제가 OFFLINE_REQUIRED면 그 결제', () => {
    const payments = [payment({ paymentId: 5, status: 'OFFLINE_REQUIRED' })]
    expect(rechargeablePaymentId(payments)).toBe(5)
  })

  it('PENDING이면 절대 허용하지 않는다 — 승인 미확정이라 재청구하면 이중 결제가 된다', () => {
    const payments = [payment({ paymentId: 5, status: 'PENDING' })]
    expect(rechargeablePaymentId(payments)).toBeNull()
  })

  it.each(['PAID', 'OFFLINE_PAID', 'REFUNDED'] as const)(
    '%s 상태에서는 재청구 대상이 아니다',
    (status) => {
      expect(rechargeablePaymentId([payment({ paymentId: 5, status })])).toBeNull()
    },
  )

  it('대체된 과거 결제가 OFFLINE_REQUIRED여도 활성이 아니면 대상이 아니다', () => {
    const payments = [
      payment({ paymentId: 10, status: 'OFFLINE_REQUIRED', createdAt: '2026-08-10T10:00:00' }),
      payment({ paymentId: 11, status: 'PAID', createdAt: '2026-08-13T10:00:00' }),
    ]
    // 활성은 11(PAID)이므로 재청구 대상이 없다 — 과거 10에 버튼이 붙으면 이미 낸 돈을 또 받게 된다.
    expect(rechargeablePaymentId(payments)).toBeNull()
  })

  it('재청구가 또 실패해 활성이 다시 OFFLINE_REQUIRED면 최신 결제가 대상', () => {
    const payments = [
      payment({ paymentId: 10, status: 'OFFLINE_REQUIRED', createdAt: '2026-08-10T10:00:00' }),
      payment({ paymentId: 11, status: 'OFFLINE_REQUIRED', createdAt: '2026-08-13T10:00:00' }),
    ]
    expect(rechargeablePaymentId(payments)).toBe(11)
  })
})
