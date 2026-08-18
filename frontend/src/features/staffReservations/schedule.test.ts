// groupByDate 정렬·그룹핑과 isOverdue 판정 검증.
import { describe, expect, it, vi, afterEach } from 'vitest'
import { groupByDate, isOverdue, isUpcomingStatus } from './schedule'
import type { StaffReservationListItem } from './types'
import { ReservationStatus } from '@/types/enums'

function item(
  id: number,
  reservedAt: string,
  status: ReservationStatus = ReservationStatus.CONFIRMED,
): StaffReservationListItem {
  return {
    reservationId: id,
    memberId: 1,
    petId: 1,
    petName: `pet${id}`,
    reservedAt,
    reservationStatus: status,
    rejectionReason: null,
    reservationHistory: {
      totalReservationCount: 0,
      completedCount: 0,
      cancelCount: 0,
      noShowCount: 0,
    },
  }
}

afterEach(() => vi.useRealTimers())

describe('groupByDate', () => {
  it('날짜별로 묶고 그룹 안을 이른 시간부터 정렬한다(ascending)', () => {
    const items = [
      item(1, '2026-08-20T14:00:00'),
      item(2, '2026-08-19T11:00:00'),
      item(3, '2026-08-19T09:00:00'),
    ]
    const groups = groupByDate(items, true)
    expect(groups.map((g) => g.date)).toEqual(['2026-08-19', '2026-08-20'])
    expect(groups[0].items.map((i) => i.reservationId)).toEqual([3, 2])
    expect(groups[1].items.map((i) => i.reservationId)).toEqual([1])
  })

  it('descending이면 최근 날짜·늦은 시간부터', () => {
    const groups = groupByDate(
      [item(1, '2026-08-19T09:00:00'), item(2, '2026-08-20T10:00:00')],
      false,
    )
    expect(groups.map((g) => g.date)).toEqual(['2026-08-20', '2026-08-19'])
  })
})

describe('isOverdue', () => {
  it('예약 시각이 지난 CONFIRMED는 지연으로 본다', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-19T12:00:00'))
    expect(isOverdue(item(1, '2026-08-19T10:00:00', ReservationStatus.CONFIRMED))).toBe(true)
    expect(isOverdue(item(2, '2026-08-19T14:00:00', ReservationStatus.CONFIRMED))).toBe(false)
  })

  it('내원 대기가 아닌 상태(REQUESTED·CHECKED_IN)는 지연으로 보지 않는다', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-19T12:00:00'))
    expect(isOverdue(item(1, '2026-08-19T10:00:00', ReservationStatus.REQUESTED))).toBe(false)
    expect(isOverdue(item(2, '2026-08-19T10:00:00', ReservationStatus.CHECKED_IN))).toBe(false)
  })
})

describe('isUpcomingStatus', () => {
  it('요청·확정 등은 예정, 완료·취소는 이력', () => {
    expect(isUpcomingStatus(ReservationStatus.REQUESTED)).toBe(true)
    expect(isUpcomingStatus(ReservationStatus.CONFIRMED)).toBe(true)
    expect(isUpcomingStatus(ReservationStatus.TREATMENT_COMPLETED)).toBe(false)
    expect(isUpcomingStatus(ReservationStatus.CANCELED)).toBe(false)
  })
})
