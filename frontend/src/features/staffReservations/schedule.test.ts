// groupByDate 정렬·그룹핑과 isOverdue 판정 검증.
// 시각 비교는 Asia/Seoul 기준 문자열로 하므로, 기준 시각을 인자로 주입해 테스트가 실행 환경의
// 타임존에 흔들리지 않게 한다(리뷰 P2 — 예전엔 로컬 Date 파싱이라 UTC CI에서 결과가 달라졌다).
import { afterEach, describe, expect, it, vi } from 'vitest'
import { dateLabel, groupByDate, isOverdue, isUpcomingStatus } from './schedule'
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
  // 기준 시각을 ms로 주입한다 — 서울 12:00은 UTC 03:00이다.
  const noonSeoul = Date.parse('2026-08-19T12:00:00+09:00')

  it('예약 시각이 지난 CONFIRMED는 지연으로 본다', () => {
    expect(
      isOverdue(item(1, '2026-08-19T10:00:00', ReservationStatus.CONFIRMED), noonSeoul),
    ).toBe(true)
    expect(
      isOverdue(item(2, '2026-08-19T14:00:00', ReservationStatus.CONFIRMED), noonSeoul),
    ).toBe(false)
  })

  it('내원 대기가 아닌 상태(REQUESTED·CHECKED_IN)는 지연으로 보지 않는다', () => {
    expect(
      isOverdue(item(1, '2026-08-19T10:00:00', ReservationStatus.REQUESTED), noonSeoul),
    ).toBe(false)
    expect(
      isOverdue(item(2, '2026-08-19T10:00:00', ReservationStatus.CHECKED_IN), noonSeoul),
    ).toBe(false)
  })

  it('기준 시각을 주면 그 값과만 비교한다', () => {
    const item9am = item(1, '2026-08-20T09:00:00', ReservationStatus.CONFIRMED)
    expect(isOverdue(item9am, Date.parse('2026-08-20T08:30:00+09:00'))).toBe(false)
    expect(isOverdue(item9am, Date.parse('2026-08-20T09:30:00+09:00'))).toBe(true)
  })

  /*
    기준 시각을 주지 않으면 "지금"을 Asia/Seoul로 읽는다. 예전 구현은 예약 시각을 브라우저 로컬로
    파싱해 실제 순간(Date.now())과 비교했기 때문에, 실행 환경이 KST가 아니면 판정이 최대 9시간
    어긋났다. 실제 클럭을 UTC 순간으로 고정해 그 회귀를 잡는다 — 이 테스트는 프로세스 타임존이
    무엇이든 같은 결과여야 한다.
  */
  it('기준 시각을 주지 않으면 실행 환경 타임존과 무관하게 서울 기준으로 판정한다', () => {
    // 2026-08-20T00:30:00Z = 서울 09:30. 서울 09:00 예약은 지났고, 10:00 예약은 아직이다.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-20T00:30:00Z'))

    expect(
      isOverdue(item(1, '2026-08-20T09:00:00', ReservationStatus.CONFIRMED)),
    ).toBe(true)
    expect(
      isOverdue(item(2, '2026-08-20T10:00:00', ReservationStatus.CONFIRMED)),
    ).toBe(false)
  })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('dateLabel', () => {
  // 기준 날짜(Asia/Seoul)를 주입해 "오늘"·"내일"이 실행 환경 날짜에 흔들리지 않게 한다.
  it('기준 날짜와 같으면 오늘, 하루 뒤면 내일', () => {
    expect(dateLabel('2026-08-20', '2026-08-20')).toBe('오늘')
    expect(dateLabel('2026-08-21', '2026-08-20')).toBe('내일')
  })

  it('월말을 넘겨도 내일을 맞게 계산한다', () => {
    expect(dateLabel('2026-09-01', '2026-08-31')).toBe('내일')
  })

  /*
    그 밖의 날짜는 로케일 포맷을 그대로 쓴다. 정확한 표기는 ICU 버전에 따라 달라질 수 있어
    문자열 전체를 단언하지 않고, 날짜 정보가 들어 있고 오늘·내일로 오분류되지 않는지만 본다.
  */
  it('그 밖의 날짜는 월·일이 담긴 라벨로 표시한다', () => {
    const label = dateLabel('2026-08-25', '2026-08-20')
    expect(label).toContain('8월')
    expect(label).toContain('25')
    expect(label).not.toBe('오늘')
    expect(label).not.toBe('내일')
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
