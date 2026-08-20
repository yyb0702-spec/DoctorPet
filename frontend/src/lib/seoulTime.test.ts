// 오프셋 없는 백엔드 시각을 다루는 유틸. 실행 환경 타임존이 결과를 바꾸지 않아야 한다.
import { describe, expect, it } from 'vitest'
import {
  naiveDateTimeLabel,
  naiveTimeLabel,
  parseSeoulDateTime,
  shiftDateKey,
  todaySeoulKey,
} from './seoulTime'

describe('parseSeoulDateTime', () => {
  /*
    오프셋 없는 값은 서울(UTC+9)로 해석해야 한다. `new Date(...)`에 그냥 넣으면 브라우저 로컬로
    해석되므로, TZ가 무엇이든 같은 순간이 나오는지 절대 기준(Date.UTC)과 비교해 고정한다.
  */
  it('오프셋이 없으면 +09:00으로 해석한다', () => {
    // 서울 09:00 = 같은 날 00:00 UTC.
    expect(parseSeoulDateTime('2026-08-20T09:00:00')).toBe(
      Date.UTC(2026, 7, 20, 0, 0, 0),
    )
  })

  it('이미 오프셋·Z가 있으면 그대로 신뢰한다', () => {
    expect(parseSeoulDateTime('2026-08-20T00:00:00Z')).toBe(
      Date.UTC(2026, 7, 20, 0, 0, 0),
    )
    expect(parseSeoulDateTime('2026-08-20T09:00:00+09:00')).toBe(
      Date.UTC(2026, 7, 20, 0, 0, 0),
    )
  })
})

describe('todaySeoulKey', () => {
  it('yyyy-MM-dd 형식의 서울 날짜를 준다', () => {
    expect(todaySeoulKey()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(todaySeoulKey()).toBe(
      new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' }),
    )
  })
})

describe('shiftDateKey', () => {
  it('하루를 더하고 월·연 경계를 넘긴다', () => {
    expect(shiftDateKey('2026-08-20', 1)).toBe('2026-08-21')
    expect(shiftDateKey('2026-08-31', 1)).toBe('2026-09-01')
    expect(shiftDateKey('2026-12-31', 1)).toBe('2027-01-01')
    expect(shiftDateKey('2026-03-01', -1)).toBe('2026-02-28')
  })
})

describe('naive 라벨', () => {
  it('시각 문자열을 파싱하지 않고 그대로 읽는다', () => {
    expect(naiveTimeLabel('2026-08-20T09:05:00')).toBe('09:05')
    expect(naiveDateTimeLabel('2026-08-20T09:05:00')).toBe('2026년 8월 20일 09:05')
  })

  it('소수점 초가 있어도 같은 라벨을 만든다', () => {
    expect(naiveTimeLabel('2026-08-20T09:05:00.123')).toBe('09:05')
  })

  /*
    형식이 다르면 슬라이스가 엉뚱한 값(빈 문자열 등)을 만들어 시각이 조용히 사라진다.
    그럴 때는 원문을 그대로 보여주는 편이 낫다 — 최소한 값이 이상하다는 게 화면에 드러난다.
  */
  it('형식이 다르면 원문을 그대로 돌려준다', () => {
    expect(naiveTimeLabel('2026-08-20')).toBe('2026-08-20')
    expect(naiveTimeLabel('')).toBe('')
    expect(naiveDateTimeLabel('nonsense')).toBe('nonsense')
  })
})
