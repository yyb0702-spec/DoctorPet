// 오프셋 없는 백엔드 시각을 다루는 유틸. 실행 환경 타임존이 결과를 바꾸지 않아야 한다.
import { describe, expect, it } from 'vitest'
import {
  dateKeyLabel,
  groupByDateKey,
  naiveDateTimeLabel,
  naiveTimeLabel,
  parseSeoulDateTime,
  relativeDateKeyLabel,
  shiftDateKey,
  todaySeoulKey,
} from './seoulTime'

describe('groupByDateKey', () => {
  const items = [
    { reservedAt: '2026-08-20T15:00:00', id: 'a' },
    { reservedAt: '2026-08-23T11:00:00', id: 'b' },
    { reservedAt: '2026-08-23T10:00:00', id: 'c' },
    { reservedAt: '2026-08-24T14:00:00', id: 'd' },
  ]

  it('최근 날짜부터(내림차순) 묶고 같은 날은 한 그룹에 넣으며 오늘·내일 라벨을 쓴다', () => {
    const groups = groupByDateKey(
      items,
      (i) => i.reservedAt,
      false,
      '2026-08-23',
    )

    expect(groups.map((g) => g.date)).toEqual([
      '2026-08-24',
      '2026-08-23',
      '2026-08-20',
    ])
    expect(groups.map((g) => g.label)).toEqual([
      '내일',
      '오늘',
      '8월 20일 (목)',
    ])
    // 같은 날(8-23)은 한 그룹, 내림차순이라 늦은 시각(11:00=b)이 앞.
    expect(groups[1].items.map((i) => i.id)).toEqual(['b', 'c'])
  })

  it('ascending=true면 이른 날짜부터 묶는다', () => {
    const groups = groupByDateKey(
      items,
      (i) => i.reservedAt,
      true,
      '2026-08-23',
    )
    expect(groups.map((g) => g.date)).toEqual([
      '2026-08-20',
      '2026-08-23',
      '2026-08-24',
    ])
    expect(groups[1].items.map((i) => i.id)).toEqual(['c', 'b'])
  })

  it('빈 배열은 빈 그룹 목록을 준다', () => {
    expect(
      groupByDateKey<{ reservedAt: string }>(
        [],
        (i) => i.reservedAt,
        false,
        '2026-08-23',
      ),
    ).toEqual([])
  })

  it('todayKey를 주지 않아도 동작한다(기본값 주입)', () => {
    const groups = groupByDateKey(
      [{ reservedAt: '2026-08-20T09:00:00', id: 'x' }],
      (i) => i.reservedAt,
      false,
    )
    expect(groups).toHaveLength(1)
    expect(groups[0].date).toBe('2026-08-20')
    expect(groups[0].items.map((i) => i.id)).toEqual(['x'])
  })
})

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
    expect(naiveDateTimeLabel('2026-08-20T09:05:00')).toBe(
      '2026년 8월 20일 09:05',
    )
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

describe('날짜 키 라벨', () => {
  it('연도까지 필요한 표기는 연·월·일을 준다', () => {
    expect(dateKeyLabel('2026-08-22')).toBe('2026년 8월 22일')
    expect(dateKeyLabel('2026-12-01')).toBe('2026년 12월 1일')
  })

  // 형식이 다르면 split이 NaN을 만들어 "NaN년 …"이 화면에 뜬다. 원문을 그대로 보여주는 편이 낫다.
  it('형식이 다르면 원문을 그대로 돌려준다', () => {
    expect(dateKeyLabel('')).toBe('')
    expect(dateKeyLabel('2026-08')).toBe('2026-08')
    expect(dateKeyLabel('2026-08-22T09:00')).toBe('2026-08-22T09:00')
  })

  // 기준 날짜(Asia/Seoul)를 주입해 "오늘"·"내일"이 실행 환경 날짜에 흔들리지 않게 한다.
  it('기준 날짜와 같으면 오늘, 하루 뒤면 내일', () => {
    expect(relativeDateKeyLabel('2026-08-20', '2026-08-20')).toBe('오늘')
    expect(relativeDateKeyLabel('2026-08-21', '2026-08-20')).toBe('내일')
  })

  it('월말을 넘겨도 내일을 맞게 계산한다', () => {
    expect(relativeDateKeyLabel('2026-09-01', '2026-08-31')).toBe('내일')
  })

  /*
    그 밖의 날짜는 로케일 포맷을 그대로 쓴다. 정확한 표기는 ICU 버전에 따라 달라질 수 있어
    문자열 전체를 단언하지 않고, 날짜 정보가 들어 있고 오늘·내일로 오분류되지 않는지만 본다.
  */
  it('그 밖의 날짜는 월·일이 담긴 상대 라벨로 표시한다', () => {
    const label = relativeDateKeyLabel('2026-08-25', '2026-08-20')
    expect(label).toContain('8월')
    expect(label).toContain('25')
    expect(label).not.toBe('오늘')
    expect(label).not.toBe('내일')
  })
})
