// 대기 신청 버튼 차단 사유 — 상태별로 재신청 가능 여부가 갈리는지 검증.
import { describe, expect, it } from 'vitest'
import { waitlistBlockReason } from './slotBlock'
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

describe('waitlistBlockReason', () => {
  it('대기열 이력이 없으면 막지 않는다', () => {
    expect(waitlistBlockReason([], 501)).toBeNull()
  })

  it('WAITING이면 대기중으로 막는다', () => {
    const result = waitlistBlockReason([waitlist({ status: 'WAITING' })], 501)
    expect(result).toBe('PENDING')
  })

  it('OFFERED면 대기중으로 막는다', () => {
    const result = waitlistBlockReason([waitlist({ status: 'OFFERED' })], 501)
    expect(result).toBe('PENDING')
  })

  it('ACCEPTED면 재신청이 아니라 예약완료로 막는다 — 백엔드가 재활성화하지 않는 상태다', () => {
    const result = waitlistBlockReason([waitlist({ status: 'ACCEPTED' })], 501)
    expect(result).toBe('ACCEPTED')
  })

  it.each(['CANCELED', 'REJECTED', 'EXPIRED'] as const)(
    '%s는 재신청을 막지 않는다 — 백엔드가 재활성화하는 종료 상태다',
    (status) => {
      expect(waitlistBlockReason([waitlist({ status })], 501)).toBeNull()
    },
  )

  it('같은 슬롯에 ACCEPTED 이력이 하나라도 있으면 다른 종료 이력이 섞여 있어도 예약완료로 막는다', () => {
    const result = waitlistBlockReason(
      [
        waitlist({ waitlistId: 1, status: 'EXPIRED' }),
        waitlist({ waitlistId: 2, status: 'ACCEPTED' }),
      ],
      501,
    )
    expect(result).toBe('ACCEPTED')
  })

  it('다른 슬롯의 대기열은 영향을 주지 않는다', () => {
    const result = waitlistBlockReason(
      [waitlist({ slotId: 999, status: 'WAITING' })],
      501,
    )
    expect(result).toBeNull()
  })
})
