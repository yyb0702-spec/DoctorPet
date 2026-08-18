// 활성 결제 판정 — 1:N 결제 이력에서 최신(=활성)을 고르는지 검증(정정 재청구 게이팅의 근거, SA §9-4).
import { describe, expect, it } from 'vitest'
import { activePaymentId } from './activePayment'
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
