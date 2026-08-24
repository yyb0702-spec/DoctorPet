// 진료시간 편집 보조 — 백엔드 검증 규칙을 그대로 옮긴 클라이언트 검증과 폼 변환.
//
// 백엔드(HospitalOperatingHoursApplicationService.validateAndConvert)의 규칙을 그대로 따른다:
//  - 시작 시각과 종료 시각이 같으면 무효(INVALID_OPERATING_HOURS)
//  - 종료가 시작보다 이르면 **자정을 넘긴 야간 진료**로 해석한다(무효가 아니다)
//  - 요일 안에서도, 주 전체에서도 구간이 겹치면 무효 — 일요일 야간이 다음 주 월요일과 겹치는
//    경우까지 본다(백엔드가 +1주 이동 비교로 검사한다)
// 규칙을 프론트가 다르게 구현하면 유효한 입력을 막거나 무효한 입력을 서버까지 보내게 되므로
// 여기 한 곳에만 두고 페이지는 이 함수만 쓴다.
import { DAY_ORDER, type DailyOperatingHours, type DayOfWeek } from './types'

const MINUTES_PER_DAY = 1440
const MINUTES_PER_WEEK = MINUTES_PER_DAY * 7

export interface OperatingHoursProblem {
  dayOfWeek: DayOfWeek
  message: string
}

interface WeekInterval {
  dayOfWeek: DayOfWeek
  start: number
  end: number
}

/** 서버가 "09:00:00"으로 줄 수도 있어 <input type="time">이 받는 "HH:mm"으로 자른다. */
export function toTimeInputValue(time: string): string {
  return time.slice(0, 5)
}

/** 7요일 전부 휴무인 빈 주. 요청 days는 항상 7개여야 한다. */
export function emptyWeek(): DailyOperatingHours[] {
  return DAY_ORDER.map((dayOfWeek) => ({ dayOfWeek, periods: [] }))
}

/** 응답을 폼 상태로 — 요일 순서를 고정하고 누락된 요일은 휴무로 채운다. */
export function toWeekDraft(
  days: DailyOperatingHours[],
): DailyOperatingHours[] {
  return DAY_ORDER.map((dayOfWeek) => {
    const found = days.find((day) => day.dayOfWeek === dayOfWeek)
    return {
      dayOfWeek,
      periods: (found?.periods ?? []).map((period) => ({
        startTime: toTimeInputValue(period.startTime),
        endTime: toTimeInputValue(period.endTime),
      })),
    }
  })
}

function toMinutes(time: string): number | null {
  const matched = /^(\d{1,2}):(\d{2})$/.exec(time)
  if (!matched) return null
  const hour = Number(matched[1])
  const minute = Number(matched[2])
  if (hour > 23 || minute > 59) return null
  return hour * 60 + minute
}

function overlaps(
  first: WeekInterval,
  secondStart: number,
  secondEnd: number,
): boolean {
  return first.start < secondEnd && secondStart < first.end
}

export function validateWeeklyOperatingHours(
  days: DailyOperatingHours[],
): OperatingHoursProblem[] {
  const problems: OperatingHoursProblem[] = []
  const seen = new Set<string>()
  const push = (dayOfWeek: DayOfWeek, message: string) => {
    const key = `${dayOfWeek}|${message}`
    if (seen.has(key)) return
    seen.add(key)
    problems.push({ dayOfWeek, message })
  }

  const intervals: WeekInterval[] = []
  DAY_ORDER.forEach((dayOfWeek, dayIndex) => {
    const daily = days.find((day) => day.dayOfWeek === dayOfWeek)
    for (const period of daily?.periods ?? []) {
      const start = toMinutes(period.startTime)
      const end = toMinutes(period.endTime)
      if (start === null || end === null) {
        push(dayOfWeek, '진료 시작·종료 시각을 모두 입력해 주세요.')
        continue
      }
      if (start === end) {
        push(dayOfWeek, '시작 시각과 종료 시각이 같습니다.')
        continue
      }
      const base = dayIndex * MINUTES_PER_DAY
      // 종료가 더 이르면 자정을 넘긴 것으로 본다(백엔드와 동일).
      const endOffset = end < start ? end + MINUTES_PER_DAY : end
      intervals.push({ dayOfWeek, start: base + start, end: base + endOffset })
    }
  })

  const overlapMessage = '다른 진료 시간과 겹칩니다(자정을 넘긴 구간 포함).'
  for (let first = 0; first < intervals.length; first += 1) {
    for (let second = first + 1; second < intervals.length; second += 1) {
      const other = intervals[second]
      if (overlaps(intervals[first], other.start, other.end)) {
        push(intervals[first].dayOfWeek, overlapMessage)
        push(other.dayOfWeek, overlapMessage)
      }
    }
  }
  // 주가 반복되므로 다음 주로 한 주 밀어 비교한다(일요일 야간 ↔ 월요일 아침).
  for (const first of intervals) {
    for (const second of intervals) {
      if (
        overlaps(
          first,
          second.start + MINUTES_PER_WEEK,
          second.end + MINUTES_PER_WEEK,
        )
      ) {
        push(first.dayOfWeek, overlapMessage)
        push(second.dayOfWeek, overlapMessage)
      }
    }
  }

  return problems
}
