// 진료시간 클라이언트 검증이 백엔드 규칙과 같은 판정을 내리는지 확인한다.
// 특히 "자정 넘김은 유효", "주 경계(일→월) 겹침도 무효"를 놓치면 유효 입력을 막거나
// 무효 입력을 서버까지 보낸다.
import { describe, expect, it } from 'vitest'
import {
  emptyWeek,
  toWeekDraft,
  validateWeeklyOperatingHours,
} from './operatingHours'
import { DAY_ORDER, type DailyOperatingHours } from './types'

function week(
  overrides: Partial<
    Record<(typeof DAY_ORDER)[number], { startTime: string; endTime: string }[]>
  >,
): DailyOperatingHours[] {
  return emptyWeek().map((day) => ({
    ...day,
    periods: overrides[day.dayOfWeek] ?? [],
  }))
}

describe('validateWeeklyOperatingHours', () => {
  it('평일 단일 구간·주말 휴무는 문제가 없다', () => {
    const days = week({
      MONDAY: [{ startTime: '09:00', endTime: '18:00' }],
      TUESDAY: [{ startTime: '09:00', endTime: '18:00' }],
      SATURDAY: [{ startTime: '10:00', endTime: '14:00' }],
    })

    expect(validateWeeklyOperatingHours(days)).toEqual([])
  })

  it('점심 휴게로 나눈 두 구간은 문제가 없다', () => {
    const days = week({
      WEDNESDAY: [
        { startTime: '09:00', endTime: '13:00' },
        { startTime: '14:00', endTime: '18:00' },
      ],
    })

    expect(validateWeeklyOperatingHours(days)).toEqual([])
  })

  it('종료가 시작보다 이른 야간 진료는 자정 넘김으로 보고 허용한다', () => {
    const days = week({ FRIDAY: [{ startTime: '22:00', endTime: '02:00' }] })

    expect(validateWeeklyOperatingHours(days)).toEqual([])
  })

  it('시작과 종료가 같으면 무효다', () => {
    const days = week({ MONDAY: [{ startTime: '09:00', endTime: '09:00' }] })

    expect(validateWeeklyOperatingHours(days)).toEqual([
      { dayOfWeek: 'MONDAY', message: '시작 시각과 종료 시각이 같습니다.' },
    ])
  })

  it('시각이 비어 있으면 입력을 요구한다', () => {
    const days = week({ MONDAY: [{ startTime: '', endTime: '18:00' }] })

    expect(validateWeeklyOperatingHours(days)).toEqual([
      {
        dayOfWeek: 'MONDAY',
        message: '진료 시작·종료 시각을 모두 입력해 주세요.',
      },
    ])
  })

  it('같은 요일 안에서 겹치면 무효다', () => {
    const days = week({
      MONDAY: [
        { startTime: '09:00', endTime: '13:00' },
        { startTime: '12:00', endTime: '18:00' },
      ],
    })

    expect(validateWeeklyOperatingHours(days)).toHaveLength(1)
    expect(validateWeeklyOperatingHours(days)[0].dayOfWeek).toBe('MONDAY')
  })

  it('자정을 넘긴 구간이 다음 날 구간과 겹치면 무효다', () => {
    const days = week({
      MONDAY: [{ startTime: '22:00', endTime: '03:00' }],
      TUESDAY: [{ startTime: '02:00', endTime: '18:00' }],
    })

    const problems = validateWeeklyOperatingHours(days)

    expect(problems.map((problem) => problem.dayOfWeek)).toEqual([
      'MONDAY',
      'TUESDAY',
    ])
  })

  it('일요일 야간이 다음 주 월요일 아침과 겹치면 무효다(주 경계)', () => {
    const days = week({
      MONDAY: [{ startTime: '00:30', endTime: '09:00' }],
      SUNDAY: [{ startTime: '23:00', endTime: '01:00' }],
    })

    const problems = validateWeeklyOperatingHours(days)

    expect(problems.map((problem) => problem.dayOfWeek).sort()).toEqual([
      'MONDAY',
      'SUNDAY',
    ])
  })
})

describe('toWeekDraft', () => {
  it('초까지 온 시각을 HH:mm으로 자르고 7요일을 순서대로 채운다', () => {
    const draft = toWeekDraft([
      {
        dayOfWeek: 'WEDNESDAY',
        periods: [{ startTime: '09:00:00', endTime: '18:00:00' }],
      },
    ])

    expect(draft.map((day) => day.dayOfWeek)).toEqual([...DAY_ORDER])
    expect(draft[2]).toEqual({
      dayOfWeek: 'WEDNESDAY',
      periods: [{ startTime: '09:00', endTime: '18:00' }],
    })
    expect(draft[0].periods).toEqual([])
  })
})
