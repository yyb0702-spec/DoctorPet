// 스태프 예약 목록의 날짜 그룹핑·정렬·지연 판정 헬퍼.
// 목록 API에 날짜 필터·정렬이 없어(SA §8-6) 불러온 페이지 안에서 클라이언트가 정리한다.
import type { StaffReservationListItem } from './types'
import { ReservationStatus } from '@/types/enums'

// 다가오는(예정) 상태 — 이른 시간부터 보여준다. 그 외(이력) 상태는 최근부터.
const UPCOMING_STATUSES: ReservationStatus[] = [
  ReservationStatus.REQUESTED,
  ReservationStatus.CONFIRMED,
  ReservationStatus.NO_SHOW_PENDING,
  ReservationStatus.CHECKED_IN,
  ReservationStatus.IN_TREATMENT,
]

export function isUpcomingStatus(status: ReservationStatus): boolean {
  return UPCOMING_STATUSES.includes(status)
}

// reservedAt("yyyy-MM-ddTHH:mm:ss", 타임존 없음)의 날짜 부분만 그룹 키로 쓴다 — Date 파싱의
// 타임존 흔들림 없이 안정적이다.
function dateKey(reservedAt: string): string {
  return reservedAt.slice(0, 10)
}

export interface DateGroup {
  date: string // yyyy-MM-dd
  label: string // "오늘"·"내일"·"8월 19일 (수)"
  items: StaffReservationListItem[]
}

function localTodayKey(): string {
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function dateLabel(key: string): string {
  const today = localTodayKey()
  if (key === today) return '오늘'
  // 하루 뒤(내일) 계산 — 문자열 비교가 아니라 실제 날짜로.
  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  const ty = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`
  if (key === ty) return '내일'
  return new Date(`${key}T00:00:00`).toLocaleDateString('ko-KR', {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  })
}

// 예약 시각으로 정렬한 뒤 날짜별로 묶는다. ascending=true면 이른 시간부터.
export function groupByDate(
  items: StaffReservationListItem[],
  ascending: boolean,
): DateGroup[] {
  const sorted = [...items].sort((a, b) => {
    const cmp = a.reservedAt.localeCompare(b.reservedAt)
    return ascending ? cmp : -cmp
  })

  const groups: DateGroup[] = []
  const indexByKey = new Map<string, number>()
  for (const item of sorted) {
    const key = dateKey(item.reservedAt)
    let idx = indexByKey.get(key)
    if (idx === undefined) {
      idx = groups.length
      indexByKey.set(key, idx)
      groups.push({ date: key, label: dateLabel(key), items: [] })
    }
    groups[idx].items.push(item)
  }
  return groups
}

// 내원 대기 상태(확정·노쇼 확인중)인데 예약 시각이 이미 지났으면 지연 — 자동 노쇼 판정 대상이다.
export function isOverdue(item: StaffReservationListItem): boolean {
  const awaitingArrival =
    item.reservationStatus === ReservationStatus.CONFIRMED ||
    item.reservationStatus === ReservationStatus.NO_SHOW_PENDING
  return awaitingArrival && new Date(item.reservedAt).getTime() < Date.now()
}
