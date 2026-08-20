// 오프셋 없는 백엔드 시각을 다루는 유틸. 실행 환경 타임존이 결과를 바꾸지 않아야 한다.
import { describe, expect, it } from 'vitest'
import {
  naiveDateTimeLabel,
  naiveTimeLabel,
  nowSeoul,
  shiftDateKey,
  todaySeoulKey,
} from './seoulTime'

describe('nowSeoul', () => {
  it('백엔드 LocalDateTime과 같은 모양("yyyy-MM-ddTHH:mm:ss")을 준다', () => {
    // 같은 형식이어야 문자열 비교가 시간 비교와 같아진다(isOverdue가 이 성질에 의존한다).
    expect(nowSeoul()).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
  })

  it('todaySeoulKey는 그 값의 날짜 부분이다', () => {
    expect(todaySeoulKey()).toBe(nowSeoul().slice(0, 10))
  })

  /*
    프로세스 타임존(TZ)이 무엇이든 서울 기준 시각을 준다. UTC 환경에서 로컬 시각을 쓰면 한국보다
    9시간 이르므로, 두 값을 비교해 서울 값이 로컬 값보다 앞서지 않는지로 확인한다.
  */
  it('로컬 타임존이 아니라 Asia/Seoul 기준이다', () => {
    const local = new Date()
      .toLocaleString('sv-SE', { timeZone: 'Asia/Seoul' })
      .replace(' ', 'T')
    expect(nowSeoul()).toBe(local)
  })
})

describe('shiftDateKey', () => {
  it('하루를 더한다', () => {
    expect(shiftDateKey('2026-08-20', 1)).toBe('2026-08-21')
  })

  it('월·연 경계를 넘긴다', () => {
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

  // 소수점 초가 붙어 와도(LocalDateTime 직렬화 편차) 표시가 깨지지 않는다.
  it('소수점 초가 있어도 같은 라벨을 만든다', () => {
    expect(naiveTimeLabel('2026-08-20T09:05:00.123')).toBe('09:05')
  })
})
