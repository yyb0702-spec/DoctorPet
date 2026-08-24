// 대기열 목록 폴링 게이트 — 활성 상태가 남아있을 때만 계속 도는지 검증.
import { describe, expect, it } from 'vitest'
import { hasActiveWaitlist } from './hooks'
import type { Waitlist } from './types'

function waitlist(overrides: Partial<Waitlist> = {}): Waitlist {
  return {
    waitlistId: 1,
    slotId: 501,
    status: 'WAITING',
    requestedAt: '2026-08-19T09:00:00',
    offeredAt: null,
    offerExpiresAt: null,
    respondedAt: null,
    canceledAt: null,
    ...overrides,
  }
}

describe('hasActiveWaitlist', () => {
  it('데이터가 없으면(아직 첫 조회 전) 활성으로 보지 않는다', () => {
    expect(hasActiveWaitlist(undefined)).toBe(false)
  })

  it('빈 목록이면 활성으로 보지 않는다', () => {
    expect(hasActiveWaitlist([])).toBe(false)
  })

  it('WAITING이 하나라도 있으면 활성이다', () => {
    expect(hasActiveWaitlist([waitlist({ status: 'WAITING' })])).toBe(true)
  })

  it('OFFERED가 하나라도 있으면 활성이다', () => {
    expect(hasActiveWaitlist([waitlist({ status: 'OFFERED' })])).toBe(true)
  })

  it.each(['ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELED'] as const)(
    '%s만 남아 있으면 활성이 아니다 — 폴링을 멈춰야 한다',
    (status) => {
      expect(hasActiveWaitlist([waitlist({ status })])).toBe(false)
    },
  )

  it('종료 상태 사이에 활성 상태가 하나라도 섞여 있으면 활성이다', () => {
    const data = [
      waitlist({ waitlistId: 1, status: 'EXPIRED' }),
      waitlist({ waitlistId: 2, status: 'WAITING' }),
    ]
    expect(hasActiveWaitlist(data)).toBe(true)
  })
})
