// 결제수단 표시명 — 겹칠 때만 등록 시각을 덧붙여 구분하는지 검증.
import { describe, expect, it } from 'vitest'
import { paymentMethodLabels, paymentMethodPrimaryLabel } from './methodLabel'

function method(overrides: Partial<Parameters<typeof paymentMethodLabels>[0][number]> = {}) {
  return {
    id: 1,
    cardBrand: 'KB' as string | null,
    cardLast4: '1234' as string | null,
    createdAt: '2026-08-10T10:00:00',
    ...overrides,
  }
}

describe('paymentMethodLabels', () => {
  it('겹치지 않으면 카드 정보만 짧게 보여준다', () => {
    const labels = paymentMethodLabels([
      method({ id: 1, cardBrand: 'KB', cardLast4: '1234' }),
      method({ id: 2, cardBrand: 'SHINHAN', cardLast4: '5678' }),
    ])
    expect(labels.get(1)).toBe('KB ****1234')
    expect(labels.get(2)).toBe('SHINHAN ****5678')
  })

  it('카드사·뒷자리가 같으면 그 항목들에만 등록 시각을 붙인다', () => {
    const labels = paymentMethodLabels([
      method({ id: 1, createdAt: '2026-08-10T10:00:00' }),
      method({ id: 2, createdAt: '2026-08-15T14:30:00' }),
    ])
    expect(labels.get(1)).toContain('KB ****1234 · ')
    expect(labels.get(2)).toContain('KB ****1234 · ')
    // 구분이 목적이므로 두 라벨이 실제로 달라야 한다.
    expect(labels.get(1)).not.toBe(labels.get(2))
  })

  it('겹치는 항목만 시각을 붙이고 나머지는 짧게 둔다', () => {
    const labels = paymentMethodLabels([
      method({ id: 1, cardBrand: 'KB', cardLast4: '1234' }),
      method({ id: 2, cardBrand: 'KB', cardLast4: '1234', createdAt: '2026-08-15T14:30:00' }),
      method({ id: 3, cardBrand: 'SHINHAN', cardLast4: '5678' }),
    ])
    expect(labels.get(3)).toBe('SHINHAN ****5678')
    expect(labels.get(1)).toContain(' · ')
    expect(labels.get(2)).toContain(' · ')
  })

  it('카드 정보가 없는 간편결제 빌링키는 카카오페이로 표기한다', () => {
    const labels = paymentMethodLabels([method({ id: 1, cardBrand: null, cardLast4: null })])
    expect(labels.get(1)).toBe('카카오페이')
  })

  it('paymentMethodPrimaryLabel: 카드정보 유무로 카드사·뒷자리 또는 카카오페이를 낸다', () => {
    expect(paymentMethodPrimaryLabel({ cardBrand: 'SHINHAN', cardLast4: '1234' })).toBe(
      'SHINHAN ****1234',
    )
    expect(paymentMethodPrimaryLabel({ cardBrand: null, cardLast4: null })).toBe('카카오페이')
  })

  it('카드 정보가 없는 수단이 여럿이면 등록 시각으로 구분한다', () => {
    const labels = paymentMethodLabels([
      method({ id: 1, cardBrand: null, cardLast4: null, createdAt: '2026-08-10T10:00:00' }),
      method({ id: 2, cardBrand: null, cardLast4: null, createdAt: '2026-08-15T14:30:00' }),
    ])
    expect(labels.get(1)).not.toBe(labels.get(2))
  })
})
